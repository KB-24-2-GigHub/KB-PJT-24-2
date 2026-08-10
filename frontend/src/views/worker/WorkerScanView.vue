<script setup>
/**
 * [F] 알바생 QR 스캔(탭)  ·  /worker/scan  ·  WORKER  (탭 화면)
 * 카메라 스캔 → GPS 검증 → 출근/퇴근 자동 판별·기록(단일 스캔 API).
 * 카메라·위치 권한 필요. 서버가 QR 유효성·GPS 반경·출퇴근 판별을 최종 검증한다.
 * QR 이 정적이라 시간 만료로 걸러지지 않는다 — 대리 출근 차단은 서버의 GPS 반경 검증이 전담한다.
 * 현재 #163~#169 구현 전에는 Production facade가 UNAVAILABLE이며 권한도 요청하지 않는다.
 * Development에서 worker.scan Mock을 명시적으로 켠 경우에만 아래 카메라·위치 흐름을 연다.
 * 목표 API: POST /attendance/scans → @/services/worker (scan)
 * QR 디코딩은 브라우저 내장 BarcodeDetector 사용(미지원·카메라 불가 시 토큰 직접 입력).
 */
import { QrCode, ScanLine } from 'lucide-vue-next'
import { nextTick, onBeforeUnmount, ref } from 'vue'

import AppField from '@/components/common/AppField.vue'
import BaseButton from '@/components/common/BaseButton.vue'
import BaseModal from '@/components/common/BaseModal.vue'
import { isWorkerScanAvailable, scan } from '@/services/worker'
import { useUiStore } from '@/stores/ui'
import { SCAN_TYPE } from '@/utils/constants'
import { formatDateTime } from '@/utils/format'

const ui = useUiStore()

// phase: idle | scanning | processing | result
const phase = ref('idle')
const manualMode = ref(false)
const manualToken = ref('')
const scanAvailable = isWorkerScanAvailable()
const unavailableMessage = 'QR 출퇴근은 현재 준비 중인 기능입니다.'
const errorMsg = ref(scanAvailable ? '' : unavailableMessage)
const result = ref(null)

const videoEl = ref(null)
const coords = ref(null)

let stream = null
let detector = null
let detectTimer = null

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
          latitude: pos.coords.latitude,
          longitude: pos.coords.longitude,
          accuracyMeters: pos.coords.accuracy,
          capturedAt: new Date(pos.timestamp).toISOString()
        }),
      (err) => reject(err),
      { enableHighAccuracy: true, timeout: 10000 }
    )
  })
}

async function startScan() {
  if (!scanAvailable) {
    errorMsg.value = unavailableMessage
    ui.toast(unavailableMessage, { type: 'info' })
    return
  }
  errorMsg.value = ''

  // 1) 위치 권한(출퇴근 반경 검증에 필요)
  try {
    coords.value = await getLocation()
  } catch {
    errorMsg.value = '위치 권한이 필요합니다. 브라우저에서 위치 접근을 허용해주세요.'
    ui.toast('위치 권한이 필요합니다.', { type: 'warning' })
    return
  }

  // 2) QR 디코더 지원 여부 → 미지원 시 토큰 직접 입력
  if (!('BarcodeDetector' in window)) {
    manualMode.value = true
    return
  }

  // 3) 후면 카메라 열기 → 실패 시 직접 입력으로 폴백
  try {
    stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
  } catch {
    manualMode.value = true
    ui.toast('카메라를 열 수 없어 직접 입력으로 전환합니다.', { type: 'info' })
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
 * 근태 스캔 실패를 서버 상태코드별로 구분해 안내한다(docs/rules/api.md·domain.md):
 *   410 폐기   → 사장이 교체해 더 이상 쓰지 않는 옛 QR (정적 QR 이라 시간 만료는 없다)
 *   409 상태충돌 → 이미 처리됐거나 지금 스캔 불가한 근무 상태
 *   422 검증거부 → 위치(GPS 반경)·QR 인증 실패 → 반경·QR 재확인
 * (400 등 그 외는 일반 안내) 상태는 서버가 최종 판단하므로 프론트는 문구만 분기한다.
 */
function scanErrorInfo(error) {
  if (error?.code === 'FEATURE_UNAVAILABLE') {
    return { message: 'QR 출퇴근은 현재 준비 중인 기능입니다.', type: 'info' }
  }
  const status = error?.response?.status
  if (status === 410) {
    return {
      message: '더 이상 사용하지 않는 QR이에요. 매장에 붙은 최신 QR을 스캔해주세요.',
      type: 'warning'
    }
  }
  if (status === 409) {
    return {
      message: '이미 처리됐거나 지금은 스캔할 수 없는 근무예요. 근무 상태를 확인해주세요.',
      type: 'warning'
    }
  }
  if (status === 422) {
    return {
      message: '위치·QR 인증에 실패했어요. 사업장 반경 안에서 올바른 QR을 다시 스캔해주세요.',
      type: 'danger'
    }
  }
  return {
    message: '스캔에 실패했어요. 잠시 후 QR·위치를 확인해 다시 시도해주세요.',
    type: 'danger'
  }
}

async function submitScan(qrToken) {
  if (!qrToken) return
  errorMsg.value = ''
  phase.value = 'processing'
  try {
    const res = await scan({
      qrToken,
      latitude: coords.value?.latitude,
      longitude: coords.value?.longitude,
      accuracyMeters: coords.value?.accuracyMeters,
      capturedAt: coords.value?.capturedAt,
      confirmEarlyCheckout: false
    })
    result.value = res
    phase.value = 'result'
  } catch (error) {
    const info = scanErrorInfo(error)
    errorMsg.value = info.message
    ui.toast(info.message, { type: info.type })
    phase.value = 'idle'
  }
}

/** 카메라·감지 루프 정리 */
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

async function enableManual() {
  if (!scanAvailable) {
    errorMsg.value = unavailableMessage
    ui.toast(unavailableMessage, { type: 'info' })
    return
  }
  if (!coords.value) {
    try {
      coords.value = await getLocation()
    } catch {
      // 위치 없이도 서버가 최종 검증 — UX 안내만
      ui.toast('위치 권한이 없으면 인증이 거절될 수 있어요.', { type: 'warning' })
    }
  }
  manualMode.value = true
}

async function onManualSubmit() {
  if (!manualToken.value.trim()) {
    ui.toast('QR 토큰을 입력해주세요.', { type: 'warning' })
    return
  }
  await submitScan(manualToken.value.trim())
}

function reset() {
  result.value = null
  manualMode.value = false
  manualToken.value = ''
  phase.value = 'idle'
}

const resultLabel = () => SCAN_TYPE[result.value?.scanType]?.label ?? '기록 완료'

onBeforeUnmount(stopCamera)
</script>

<template>
  <div class="worker-scan">
    <!-- 스캔 전 안내 -->
    <section v-if="phase === 'idle' && !manualMode" class="intro">
      <span class="intro-icon">
        <QrCode :size="56" />
      </span>
      <h1 class="intro-title">QR 출퇴근</h1>
      <p class="intro-desc">
        사업장 QR을 스캔하면 위치를 확인해 <strong>출근·퇴근이 자동으로</strong> 기록됩니다.
        카메라와 위치 권한이 필요해요.
      </p>
      <p v-if="errorMsg" class="error">{{ errorMsg }}</p>

      <BaseButton variant="worker" size="lg" block :disabled="!scanAvailable" @click="startScan">
        <ScanLine :size="20" />
        {{ scanAvailable ? '스캔 시작' : '준비 중' }}
      </BaseButton>
      <button type="button" class="manual-link" :disabled="!scanAvailable" @click="enableManual">
        QR 토큰 직접 입력
      </button>
    </section>

    <!-- 토큰 직접 입력(폴백) -->
    <section v-else-if="phase === 'idle' && manualMode" class="manual">
      <h1 class="intro-title">QR 토큰 입력</h1>
      <p class="intro-desc">카메라를 사용할 수 없어 QR 토큰을 직접 입력합니다.</p>
      <AppField v-model="manualToken" label="QR 토큰" placeholder="QR에 포함된 토큰" />
      <p v-if="errorMsg" class="error">{{ errorMsg }}</p>
      <BaseButton variant="worker" size="lg" block @click="onManualSubmit">인증하기</BaseButton>
      <button type="button" class="manual-link" @click="reset">뒤로</button>
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
  </div>
</template>

<style scoped>
.intro,
.manual,
.processing {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-md);
  padding: var(--space-xl) 0;
  text-align: center;
}
.manual {
  align-items: stretch;
  text-align: left;
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
.intro .btn,
.manual .btn {
  margin-top: var(--space-md);
}
.error {
  font-size: var(--text-sm);
  color: var(--color-danger);
}
.manual-link {
  margin-top: var(--space-sm);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
  text-decoration: underline;
}
.manual-link:disabled {
  cursor: not-allowed;
  opacity: 0.5;
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
