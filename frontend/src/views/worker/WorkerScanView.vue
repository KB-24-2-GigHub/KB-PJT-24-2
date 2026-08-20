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
import { useRouter } from 'vue-router'

import BaseButton from '@/components/common/BaseButton.vue'
import BaseModal from '@/components/common/BaseModal.vue'
import { newIdempotencyKey } from '@/services/http'
import { scan } from '@/services/worker'
import { useUiStore } from '@/stores/ui'
import { SCAN_TYPE } from '@/utils/constants'
import { formatDateTime } from '@/utils/format'

const ui = useUiStore()
const router = useRouter()

// phase: idle | starting | scanning | processing | confirmation | result
// starting은 위치·카메라를 여는 동안의 잠금 상태다. 이 구간에서 스캔 시작 버튼을 비활성화해
// 중복 클릭이 Stream·Timer를 중복 생성하지 못하게 한다.
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
// 감지 단일 실행 잠금 — detect()가 끝나기 전에 다음 감지가 시작되지 못하게 한다.
let detecting = false

const DETECT_INTERVAL_MS = 350
/**
 * `capturedAt`은 서버 수신 시각의 5분 전~1분 후만 허용된다(API_SPEC 근태 스캔).
 * QR을 오래 맞추는 동안 측정값이 늙어 정상 위치에서도 LOCATION_INVALID가 되므로,
 * 계약 상한보다 훨씬 짧은 기준으로 제출 직전에 다시 측정한다.
 */
const LOCATION_MAX_AGE_MS = 60_000

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

/** 측정값이 계약 신선도를 넘길 만큼 늙었는지 판정한다. */
function isLocationStale(value) {
  if (!value?.capturedAt) return true
  const capturedMs = Date.parse(value.capturedAt)
  if (Number.isNaN(capturedMs)) return true
  return Date.now() - capturedMs > LOCATION_MAX_AGE_MS
}

/** 늙은 측정값만 다시 얻는다. 실패하면 좌표 없는 요청을 보내지 않는다. */
async function ensureFreshLocation() {
  if (!isLocationStale(coords.value)) return true
  try {
    coords.value = await getLocation()
    return true
  } catch {
    return false
  }
}

async function startScan() {
  // 중복 클릭 잠금 — 위치·카메라를 여는 동안 두 번째 진입을 막는다.
  if (phase.value !== 'idle') return
  if (!capable.value) {
    errorMsg.value = unsupportedMessage
    ui.toast(unsupportedMessage, { type: 'info' })
    return
  }
  errorMsg.value = ''
  phase.value = 'starting'

  // 1) 위치 권한(출퇴근 반경 검증에 필요) — 좌표 없이는 인증 요청을 보내지 않는다.
  try {
    coords.value = await getLocation()
  } catch {
    errorMsg.value = '위치 권한이 필요합니다. 브라우저에서 위치 접근을 허용해주세요.'
    ui.toast('위치 권한이 필요합니다.', { type: 'warning' })
    phase.value = 'idle'
    return
  }
  if (phase.value !== 'starting') return

  // 2) 후면 카메라 열기
  let openedStream
  try {
    openedStream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: 'environment' }
    })
  } catch {
    errorMsg.value = '카메라 권한이 필요합니다. 브라우저 설정에서 카메라 접근을 허용해주세요.'
    ui.toast(errorMsg.value, { type: 'warning' })
    phase.value = 'idle'
    return
  }

  // 대기 중 취소·화면 이탈로 잠금이 풀렸으면 방금 연 Track을 그대로 정리한다.
  if (phase.value !== 'starting') {
    openedStream.getTracks().forEach((track) => track.stop())
    return
  }

  stream = openedStream
  phase.value = 'scanning'
  await nextTick()
  if (videoEl.value) {
    videoEl.value.srcObject = stream
    try {
      await videoEl.value.play?.()
    } catch {
      // 자동재생이 막혀도 감지 루프는 계속한다.
    }
  }

  try {
    detector = new window.BarcodeDetector({ formats: ['qr_code'] })
  } catch {
    detector = new window.BarcodeDetector()
  }
  scheduleDetect()
}

/** 감지는 겹치지 않는 단일 실행 루프다 — 이전 감지가 끝난 뒤에만 다음 감지를 예약한다. */
function scheduleDetect() {
  detectTimer = setTimeout(runDetect, DETECT_INTERVAL_MS)
}

async function runDetect() {
  detectTimer = null
  if (detecting) return
  if (phase.value !== 'scanning' || !videoEl.value) return
  detecting = true
  try {
    const codes = await detector.detect(videoEl.value)
    // detect()를 기다리는 사이 취소·제출로 상태가 바뀌었을 수 있으므로 다시 확인한다.
    // 이 재확인이 없으면 지연된 감지가 같은 QR을 새 Idempotency-Key로 다시 제출한다.
    if (phase.value !== 'scanning') return
    if (codes && codes.length > 0) {
      stopCamera()
      await submitScan(codes[0].rawValue)
      return
    }
  } catch {
    // 일시적 감지 실패는 무시하고 다음 틱에 재시도
  } finally {
    detecting = false
  }
  if (phase.value === 'scanning') scheduleDetect()
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

/**
 * `409 CONFLICT`는 같은 Key·같은 Fingerprint를 서버가 아직 처리 중이라는 뜻이며
 * 잠시 뒤 같은 Key로 재시도해야 한다(API_SPEC 멱등 Claim 표). 확정 실패로 처리해
 * Key를 버리면 앞선 요청이 뒤늦게 성공했는지 확인할 방법이 사라진다.
 * 같은 409라도 IDEMPOTENCY_KEY_REUSED·ATTENDANCE_STATE_CONFLICT는 확정 충돌이라 제외한다.
 */
const RETRIABLE_SCAN_ERROR_CODES = new Set(['CONFLICT'])

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
  // QR을 맞추는 동안 측정값이 늙었으면 다시 얻는다 — 늙은 좌표는 LOCATION_INVALID가 된다.
  if (!(await ensureFreshLocation())) {
    errorMsg.value = '현재 위치를 확인하지 못했어요. 위치 권한을 다시 확인해주세요.'
    ui.toast(errorMsg.value, { type: 'warning' })
    phase.value = 'idle'
    return
  }
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
    const code = error?.code
    const status = error?.response?.status
    // 승인 오류 Code가 붙은 응답은 서버가 판정을 끝낸 확정 실패다. 503
    // ATTENDANCE_TEMPORARILY_UNAVAILABLE도 Claim을 제거한 뒤 오므로 재확인 대상이 아니다.
    // Code 없는 무응답·5xx만 결과가 불확실 — 같은 의도를 보존해 다시 확인할 수 있게 한다.
    const retriable = RETRIABLE_SCAN_ERROR_CODES.has(code)
    const decided = Boolean(SCAN_ERROR_MESSAGES[code]) && !retriable
    const uncertain = !decided && (status === undefined || status >= 500)
    if (!retriable && !uncertain) pendingIntent.value = null
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
    clearTimeout(detectTimer)
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

/**
 * 기록을 마친 뒤의 '확인' — 상태를 비우고 홈으로 보낸다.
 *
 * reset() 과 분리해 둔다. 조기 퇴근 확인 취소도 reset() 을 쓰는데, 그쪽은 아무것도
 * 기록하지 않은 상태라 홈으로 보내면 기록이 끝난 것처럼 읽힌다. 두 경로가 같은 함수를
 * 쓰면 한쪽을 고칠 때 다른 쪽이 조용히 따라 바뀐다.
 */
function finishToHome() {
  reset()
  router.push('/worker/home')
}

const resultLabel = () => SCAN_TYPE[result.value?.scanType]?.label ?? '기록 완료'

const startButtonLabel = () => {
  if (capable.value === null) return '확인 중…'
  if (phase.value === 'starting') return '준비 중…'
  return '스캔 시작'
}

// 화면 이탈은 잠금도 함께 풀어, 아직 열리는 중이던 Stream이 startScan의 정리 경로를 타게 한다.
onBeforeUnmount(() => {
  phase.value = 'idle'
  stopCamera()
})
</script>

<template>
  <div class="worker-scan">
    <!-- 스캔 전 안내 -->
    <section v-if="phase === 'idle' || phase === 'starting'" class="intro">
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
      <BaseButton
        v-else
        variant="worker"
        size="lg"
        block
        :disabled="!capable || phase === 'starting'"
        @click="startScan"
      >
        <ScanLine :size="20" />
        {{ startButtonLabel() }}
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
        <p v-if="result.earlyCheckoutConfirmedAt" class="result-early">
          조기 퇴근 확인 {{ formatDateTime(result.earlyCheckoutConfirmedAt) }}
        </p>
      </div>
      <template #footer>
        <BaseButton variant="worker" size="lg" block @click="finishToHome">확인</BaseButton>
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
  color: var(--color-late);
}
.result-early {
  margin-top: var(--space-sm);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
</style>
