<script setup>
/**
 * [F] 알바생 QR 스캔(탭)  ·  /worker/scan  ·  WORKER  (탭 화면)
 * 카메라 스캔 → GPS 검증 → 출근/퇴근 자동 판별·기록(단일 스캔 API).
 * 카메라·위치 권한 필요. 서버가 QR 유효성·GPS 반경·출퇴근 판별을 최종 검증한다.
 * QR 이 정적이라 시간 만료로 걸러지지 않는다 — 대리 출근 차단은 서버의 GPS 반경 검증이 전담한다.
 * QR Token 직접 입력 화면은 SPEC-161-01에서 제거됐다 — 원격 대리출석 경로가 되므로 미지원·
 * 권한 거부 시에도 입력창 대신 지원 환경 안내만 표시한다.
 * API: POST /api/attendance/scans → @/services/worker (scan). SPEC-161-01.
 */
import { QrCode, ScanLine } from 'lucide-vue-next'
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

import BaseButton from '@/components/common/BaseButton.vue'
import BaseModal from '@/components/common/BaseModal.vue'
import { newIdempotencyKey } from '@/services/http'
import { scan } from '@/services/worker'
import { useUiStore } from '@/stores/ui'
import { SCAN_TYPE } from '@/utils/constants'
import { formatDateTime } from '@/utils/format'

const ui = useUiStore()

// phase: idle | scanning | processing | confirmation | result
const phase = ref('idle')
// null = 판정 중, true/false = Capability 판정 결과
const capable = ref(null)
const unsupportedMessage =
  '이 브라우저·환경은 QR 출퇴근을 지원하지 않아요. 카메라·위치 권한을 지원하는 최신 모바일 브라우저를 이용해주세요.'
const errorMsg = ref('')
const result = ref(null)
const pendingIntent = ref(null)
const pendingQrToken = ref('')

const videoEl = ref(null)
const coords = ref(null)

let stream = null
let detector = null
let detectTimer = null

/**
 * 지원 브라우저는 브랜드명·Version이 아니라 실행 시 Capability로 판정한다(SPEC-161-01).
 * HTTPS(또는 로컬 Secure Context)에서 getUserMedia·Geolocation·BarcodeDetector가 모두 있고
 * qr_code Format을 지원할 때만 스캔을 시작한다.
 */
async function checkCapability() {
  if (typeof window === 'undefined' || window.isSecureContext === false) return false
  if (!navigator.mediaDevices?.getUserMedia) return false
  if (!navigator.geolocation) return false
  if (!('BarcodeDetector' in window)) return false
  try {
    const formats = await window.BarcodeDetector.getSupportedFormats()
    return formats.includes('qr_code')
  } catch {
    return false
  }
}

onMounted(async () => {
  capable.value = await checkCapability()
  if (!capable.value) errorMsg.value = unsupportedMessage
})

/** 현재 위치 조회(Promise 래핑) */
function getLocation() {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) {
      reject(new Error('geolocation unavailable'))
      return
    }
    navigator.geolocation.getCurrentPosition(
      (pos) =>
        resolve({
          latitude: Number(pos.coords.latitude.toFixed(7)),
          longitude: Number(pos.coords.longitude.toFixed(7)),
          accuracyMeters: Number(pos.coords.accuracy.toFixed(2)),
          capturedAt: new Date(pos.timestamp).toISOString()
        }),
      (err) => reject(err),
      { enableHighAccuracy: true, timeout: 10000, maximumAge: 0 }
    )
  })
}

async function startScan() {
  if (!capable.value) {
    errorMsg.value = unsupportedMessage
    ui.toast(unsupportedMessage, { type: 'info' })
    return
  }
  errorMsg.value = ''

  // 1) 위치 권한(출퇴근 반경 검증에 필요) — 좌표 없이는 인증 요청을 보내지 않는다.
  try {
    coords.value = await getLocation()
  } catch {
    errorMsg.value = '위치 권한이 필요합니다. 브라우저에서 위치 접근을 허용해주세요.'
    ui.toast('위치 권한이 필요합니다.', { type: 'warning' })
    return
  }

  // 2) 후면 카메라 열기
  try {
    stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
  } catch {
    errorMsg.value = '카메라 권한이 필요합니다. 브라우저 설정에서 카메라 접근을 허용해주세요.'
    ui.toast(errorMsg.value, { type: 'warning' })
    return
  }

  phase.value = 'scanning'
  await nextTick()
  if (videoEl.value) {
    videoEl.value.srcObject = stream
    await videoEl.value.play().catch(() => {})
  }

  try {
    detector = new window.BarcodeDetector({ formats: ['qr_code'] })
  } catch {
    detector = new window.BarcodeDetector()
  }
  detectTimer = setInterval(runDetect, 350)
}

async function runDetect() {
  if (phase.value !== 'scanning' || !videoEl.value) return
  try {
    const codes = await detector.detect(videoEl.value)
    if (codes && codes.length > 0) {
      stopCamera()
      await submitScan(codes[0].rawValue)
    }
  } catch {
    // 일시적 감지 실패는 무시하고 다음 틱에 재시도
  }
}

/**
 * 근태 스캔 실패를 서버 오류 Code별로 구분해 안내한다(SPEC-161-01 '응답과 오류').
 * 같은 HTTP 상태(409/422)가 여러 Code를 묶으므로 status보다 code를 우선한다.
 */
const SCAN_ERROR_MESSAGES = {
  QR_INVALID: { message: 'QR을 인식하지 못했어요. 다시 스캔해주세요.', type: 'danger' },
  QR_REVOKED: {
    message: '더 이상 사용하지 않는 QR이에요. 매장에 붙은 최신 QR을 스캔해주세요.',
    type: 'warning'
  },
  WORKPLACE_LOCATION_REQUIRED: {
    message: '사업장 위치가 아직 확정되지 않았어요. 사장님께 문의해주세요.',
    type: 'warning'
  },
  LOCATION_INVALID: {
    message: '위치 정확도가 낮거나 오래됐어요. 위치 권한을 다시 확인해주세요.',
    type: 'danger'
  },
  OUTSIDE_WORKPLACE_RADIUS: {
    message: '사업장 반경을 벗어났어요. 매장 안에서 다시 스캔해주세요.',
    type: 'danger'
  },
  ATTENDANCE_WORK_CASE_NOT_FOUND: {
    message: '지금 스캔할 수 있는 근무가 없어요. 근무 일정을 확인해주세요.',
    type: 'warning'
  },
  ATTENDANCE_WORK_CASE_AMBIGUOUS: {
    message: '스캔할 근무를 하나로 정할 수 없어요. 사장님께 문의해주세요.',
    type: 'warning'
  },
  ATTENDANCE_ALREADY_COMPLETED: {
    message: '이미 오늘 근무가 완료됐어요.',
    type: 'warning'
  },
  ATTENDANCE_STATE_CONFLICT: {
    message: '이미 처리됐거나 지금은 스캔할 수 없는 근무예요. 근무 상태를 확인해주세요.',
    type: 'warning'
  },
  IDEMPOTENCY_KEY_REUSED: {
    message: '이전 요청이 아직 남아있어요. 처음부터 다시 스캔해주세요.',
    type: 'warning'
  },
  CONFLICT: {
    message: '같은 요청을 처리하고 있어요. 잠시 후 다시 확인해주세요.',
    type: 'warning'
  },
  VALIDATION_ERROR: {
    message: '요청 값이 올바르지 않아요. 위치·QR을 다시 확인해주세요.',
    type: 'danger'
  },
  ATTENDANCE_TEMPORARILY_UNAVAILABLE: {
    message: '일시적으로 처리할 수 없어요. 잠시 후 다시 시도해주세요.',
    type: 'warning'
  }
}

function scanErrorInfo(error) {
  const byCode = SCAN_ERROR_MESSAGES[error?.code]
  if (byCode) return byCode
  const status = error?.response?.status
  if (status === 403) {
    return {
      message: '요청을 승인할 수 없어요. 새로고침 후 다시 로그인해 시도해주세요.',
      type: 'danger'
    }
  }
  return {
    message: '스캔에 실패했어요. 잠시 후 QR·위치를 확인해 다시 시도해주세요.',
    type: 'danger'
  }
}

/**
 * @param {string} qrToken
 * @param {{confirmEarlyCheckout?: boolean}} [options]
 * 매 의도(최초 스캔·조기 퇴근 확인)마다 새 Idempotency Key를 발급한다(SPEC-161-01).
 * 같은 의도의 응답 유실 재시도는 sendIntent/retryPendingIntent가 이 Key와 Body를 그대로 재사용한다.
 */
async function submitScan(qrToken, { confirmEarlyCheckout = false } = {}) {
  if (!qrToken) return
  const intent = {
    payload: {
      qrToken,
      latitude: coords.value?.latitude,
      longitude: coords.value?.longitude,
      accuracyMeters: coords.value?.accuracyMeters,
      capturedAt: coords.value?.capturedAt,
      confirmEarlyCheckout
    },
    idempotencyKey: newIdempotencyKey()
  }
  pendingIntent.value = intent
  await sendIntent(intent)
}

/** 응답 유실 재확인은 최초 Body와 Key를 그대로 사용해 CHECK_OUT으로 오판되지 않게 한다. */
async function sendIntent(intent) {
  errorMsg.value = ''
  phase.value = 'processing'
  try {
    const res = await scan({ ...intent.payload, idempotencyKey: intent.idempotencyKey })
    pendingIntent.value = null
    result.value = res
    if (res.result === 'CONFIRMATION_REQUIRED') {
      pendingQrToken.value = intent.payload.qrToken
      phase.value = 'confirmation'
    } else {
      phase.value = 'result'
    }
  } catch (error) {
    const status = error?.response?.status
    // 무응답(네트워크)·5xx는 결과가 불확실 — 같은 의도를 보존해 다시 확인할 수 있게 한다.
    const uncertain = status === undefined || status >= 500
    if (!uncertain) pendingIntent.value = null
    const info = uncertain
      ? {
          message: '처리 결과를 확인하지 못했어요. 같은 요청으로 결과를 다시 확인해주세요.',
          type: 'warning'
        }
      : scanErrorInfo(error)
    errorMsg.value = info.message
    ui.toast(info.message, { type: info.type })
    phase.value = 'idle'
  }
}

async function retryPendingIntent() {
  if (pendingIntent.value) await sendIntent(pendingIntent.value)
}

/** 카메라·감지 루프 정리 — 화면 이탈·오류·완료에서 항상 호출된다. */
function stopCamera() {
  if (detectTimer) {
    clearInterval(detectTimer)
    detectTimer = null
  }
  if (stream) {
    stream.getTracks().forEach((track) => track.stop())
    stream = null
  }
  if (videoEl.value) videoEl.value.srcObject = null
}

function cancelScan() {
  stopCamera()
  phase.value = 'idle'
}

/** 조기 퇴근 확인은 QR·위치·시간창을 모두 다시 검증하므로 위치를 새로 얻는다(SPEC-161-01). */
async function confirmEarlyCheckout() {
  try {
    coords.value = await getLocation()
  } catch {
    ui.toast('조기 퇴근 확인에는 현재 위치가 필요합니다.', { type: 'warning' })
    return
  }
  await submitScan(pendingQrToken.value, { confirmEarlyCheckout: true })
}

/** 확인 취소는 CHECK_OUT 성공처럼 화면·상태를 갱신하지 않는다. */
function cancelConfirmation() {
  reset()
}

function reset() {
  result.value = null
  pendingQrToken.value = ''
  pendingIntent.value = null
  phase.value = 'idle'
}

const resultLabel = () => SCAN_TYPE[result.value?.scanType]?.label ?? '기록 완료'

onBeforeUnmount(stopCamera)
</script>

<template>
  <div class="worker-scan">
    <!-- 스캔 전 안내 -->
    <section v-if="phase === 'idle'" class="intro">
      <span class="intro-icon">
        <QrCode :size="56" />
      </span>
      <h1 class="intro-title">QR 출퇴근</h1>
      <p class="intro-desc">
        사업장 QR을 스캔하면 위치를 확인해 <strong>출근·퇴근이 자동으로</strong> 기록됩니다.
        카메라와 위치 권한이 필요해요.
      </p>
      <p v-if="errorMsg" class="error">{{ errorMsg }}</p>

      <BaseButton v-if="pendingIntent" variant="worker" size="lg" block @click="retryPendingIntent">
        같은 요청 결과 다시 확인
      </BaseButton>
      <BaseButton v-else variant="worker" size="lg" block :disabled="!capable" @click="startScan">
        <ScanLine :size="20" />
        {{ capable === null ? '확인 중…' : '스캔 시작' }}
      </BaseButton>
    </section>

    <!-- 카메라 스캔 중 -->
    <section v-else-if="phase === 'scanning'" class="scanning">
      <div class="viewport">
        <video ref="videoEl" class="video" playsinline muted></video>
        <div class="frame"></div>
      </div>
      <p class="hint">QR을 사각형 안에 맞춰주세요.</p>
      <BaseButton variant="secondary" size="lg" block @click="cancelScan">취소</BaseButton>
    </section>

    <!-- 서버 기록 중 -->
    <section v-else-if="phase === 'processing'" class="processing">
      <ScanLine :size="40" />
      <p>출퇴근을 기록하는 중…</p>
    </section>

    <!-- 결과 모달 -->
    <BaseModal :open="phase === 'result'" :closable="false" title="인증 완료">
      <div v-if="result" class="result">
        <p class="result-type">{{ resultLabel() }} 처리되었습니다.</p>
        <p class="result-time">{{ formatDateTime(result.recordedAt) }}</p>
        <p v-if="result.isLate" class="result-late">지각 {{ result.lateMinutes }}분으로 기록됨</p>
      </div>
      <template #footer>
        <BaseButton variant="worker" size="lg" block @click="reset">확인</BaseButton>
      </template>
    </BaseModal>

    <!-- 조기 퇴근 확인 모달 -->
    <BaseModal :open="phase === 'confirmation'" :closable="false" title="조기 퇴근 확인">
      <p class="intro-desc">
        예정 종료 시각 {{ formatDateTime(result?.scheduledEndAt) }} 전입니다. 지금 퇴근으로
        기록할까요?
      </p>
      <template #footer>
        <BaseButton variant="secondary" block @click="cancelConfirmation">취소</BaseButton>
        <BaseButton variant="worker" block @click="confirmEarlyCheckout">퇴근 기록</BaseButton>
      </template>
    </BaseModal>
  </div>
</template>

<style scoped>
.intro,
.processing {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-md);
  padding: var(--space-xl) 0;
  text-align: center;
}
.intro-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 96px;
  height: 96px;
  border-radius: var(--radius-pill);
  background: var(--color-worker-weak);
  color: var(--color-worker);
}
.intro-title {
  font-size: var(--text-xl);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}
.intro-desc {
  font-size: var(--text-md);
  color: var(--color-text-sub);
  line-height: 1.6;
}
.intro-desc strong {
  color: var(--color-text);
  font-weight: var(--weight-medium);
}
.intro .btn {
  margin-top: var(--space-md);
}
.error {
  font-size: var(--text-sm);
  color: var(--color-danger);
}
.scanning {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
}
.viewport {
  position: relative;
  width: 100%;
  aspect-ratio: 1;
  border-radius: var(--radius-lg);
  overflow: hidden;
  background: var(--color-primary);
}
.video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.frame {
  position: absolute;
  inset: 15%;
  border: 3px solid var(--color-on-primary);
  border-radius: var(--radius-md);
  box-shadow: 0 0 0 100vmax var(--color-overlay);
}
.hint {
  text-align: center;
  font-size: var(--text-md);
  color: var(--color-text-sub);
}
.processing {
  color: var(--color-text-sub);
}
.result {
  text-align: center;
}
.result-type {
  font-size: var(--text-lg);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}
.result-time {
  margin-top: var(--space-xs);
  font-size: var(--text-md);
  color: var(--color-text-sub);
}
.result-late {
  margin-top: var(--space-sm);
  font-size: var(--text-sm);
  color: var(--color-warning);
}
</style>
