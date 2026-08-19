<script setup>
/**
 * [C] 사장 QR  ·  /owner/qr  ·  OWNER  (탭 화면)
 * 선택 지점의 고정 QR 표시와 재발급(출력해 매장에 부착하는 용도).
 * 지점 컨텍스트: useWorkplaceStore().selectedId 기준.
 * 연계 API: GET /workplaces/{id}/qr, POST /workplaces/{id}/qr/reissue
 *   →  @/services/workplaces (getWorkplaceQr, reissueWorkplaceQr)
 *
 * 토큰 발급·검증은 서버가 한다. 만료 주기가 없어 값이 고정이므로 지점이 바뀔 때만 다시
 * 조회한다. 재발급은 사장이 직접 실행하며 기존 QR 을 즉시 폐기한다 — 되돌릴 수 없어
 * 확인 모달과 전송 중 잠금을 둔다.
 * QR 이미지는 qrcode 로 canvas 에 직접 그린다(브라우저 생성 — docs/DEPENDENCY_SPECIFICATION.md).
 */
import QRCode from 'qrcode'
import { computed, nextTick, onMounted, ref, watch } from 'vue'

import BaseButton from '@/components/common/BaseButton.vue'
import BaseModal from '@/components/common/BaseModal.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { getWorkplaceQr, reissueWorkplaceQr } from '@/services/workplaces'
import { useUiStore } from '@/stores/ui'
import { useWorkplaceStore } from '@/stores/workplace'

/**
 * 인쇄·부착이 전제라 화면 크기가 아니라 인쇄 선명도 기준으로 그린다.
 * - width: 표시 크기보다 크게 그려두고 CSS 로 축소한다(작게 그려 늘리면 인쇄물에서 뭉갠다).
 * - errorCorrectionLevel 'H': 부착물 훼손·조명 저하에도 읽히도록 최대 복원력.
 * - margin: quiet zone. 여백이 없으면 스캐너가 코드 경계를 못 찾는다.
 * - color: 테마 변수를 쓰지 않는다. 다크 테마에서 대비가 무너지면 스캔이 실패한다.
 */
const QR_OPTIONS = {
  errorCorrectionLevel: 'H', // QR 코드가 일부 훼손되거나 가려져도 얼마나 복원해서 읽을 수 있는지를 결정하는 옵션. H가 최고 레벨.
  margin: 4,
  width: 512,
  color: { dark: '#000000', light: '#ffffff' }
}

const ui = useUiStore()
const workplaceStore = useWorkplaceStore()

const qr = ref(null)
const loading = ref(false)
const canvasEl = ref(null)
const confirmOpen = ref(false)
const reissuing = ref(false)

const workplaceName = computed(() => workplaceStore.selected?.name ?? '')

/** 선택 지점의 QR 조회. 값이 고정이라 지점 전환 시에만 호출된다. */
async function load() {
  const workplaceId = workplaceStore.selectedId
  if (workplaceId == null) return

  loading.value = true
  try {
    qr.value = await getWorkplaceQr(workplaceId)
  } catch {
    qr.value = null
    ui.toast('QR을 불러오지 못했어요.', { type: 'danger' })
  } finally {
    loading.value = false
  }
}

/**
 * 재발급 확정. 성공 응답이 곧 새 활성 QR 이므로 재조회하지 않고 그대로 반영한다.
 * 전송 중에는 버튼을 잠근다 — 이 요청은 멱등 Header 를 쓰지 않으므로 두 번 보내면
 * 첫 응답으로 인쇄한 QR 이 두 번째 요청에 폐기된다.
 */
async function confirmReissue() {
  // :disabled 가 이미 막지만, 이 요청은 멱등 Header 가 없어 한 번 새면 인쇄물이 죽는다.
  // 화면 상태에만 기대지 않고 호출 지점에서도 끊는다.
  if (reissuing.value) return

  const workplaceId = workplaceStore.selectedId
  if (workplaceId == null) return

  reissuing.value = true
  try {
    qr.value = await reissueWorkplaceQr(workplaceId)
    ui.toast('QR을 새로 발급했어요. 매장에 붙인 QR을 교체해주세요.', { type: 'success' })
    confirmOpen.value = false
  } catch {
    ui.toast('QR을 재발급하지 못했어요.', { type: 'danger' })
  } finally {
    reissuing.value = false
  }
}

/** 토큰을 canvas 에 QR 로 그린다. */
async function draw() {
  const token = qr.value?.qrToken
  if (!token) return

  // canvas 는 v-if="qr" 안에 있어 토큰이 채워진 직후에는 아직 DOM 에 없다.
  await nextTick() // nextTick(): Vue에서 데이터 변경으로 인한 DOM 업데이트가 끝난 다음 실행하도록 기다리는 함수
  if (!canvasEl.value) return

  try {
    await QRCode.toCanvas(canvasEl.value, token, QR_OPTIONS)
    // qrcode 는 그리면서 canvas 에 style.width/height 를 QR_OPTIONS.width(512px) 로 직접
    // 박는다(lib/renderer/canvas.js 의 clearCanvas). 인라인 스타일이라 scoped CSS 의
    // width/height 를 이겨, 그대로 두면 표시 크기를 화면이 통제하지 못한다.
    // 실제로 그 탓에 QR 이 512px 로 상자를 뚫거나, max-width 로 폭만 눌리고 높이는
    // 512px 로 남아 세로로 늘어나 보였다.
    // width/height **속성**(512)은 지우지 않는다 — 인쇄·확대 시 선명도가 거기서 나온다.
    canvasEl.value.style.width = ''
    canvasEl.value.style.height = ''
  } catch {
    qr.value = null
    ui.toast('QR 이미지를 만들지 못했어요.', { type: 'danger' })
  }
}

onMounted(() => workplaceStore.load())

// 지점을 바꾸면 그 지점의 QR 을 다시 불러온다.
watch(() => workplaceStore.selectedId, load, { immediate: true })

// 토큰이 바뀌면(지점 전환 포함) 이미지를 다시 그린다.
watch(() => qr.value?.qrToken, draw)
</script>

<template>
  <div class="qr-screen">
    <EmptyState
      v-if="!workplaceStore.hasActiveWorkplace && workplaceStore.loaded"
      message="등록된 사업장이 없습니다."
    >
      사업장을 먼저 등록하면 출퇴근 QR을 발급할 수 있어요.
    </EmptyState>

    <template v-else>
      <header class="head">
        <h2 class="title">출퇴근 QR</h2>
        <p class="desc">
          <strong>{{ workplaceName }}</strong> 알바생이 이 QR을 스캔하면 출퇴근이 기록됩니다.
        </p>
        <p class="desc">QR은 바뀌지 않으니 출력해서 매장에 붙여두고 계속 사용하세요.</p>
      </header>

      <!-- 상자에는 QR 만 담는다. 토큰까지 넣으면 정사각형이 무너지고 QR 이 그만큼 작아진다. -->
      <div class="qr-box">
        <canvas v-if="qr" ref="canvasEl" class="qr-canvas"></canvas>
        <p v-else class="qr-placeholder">
          <template v-if="loading">QR을 불러오는 중…</template>
          <template v-else>표시할 QR이 없어요.</template>
        </p>
      </div>

      <!--
        재발급 버튼을 qr 존재 여부에 묶지 않는다. 활성 QR 이 없는 지점은 조회가 실패하는데,
        그 상태에서 사용자가 스스로 복구할 수 있는 유일한 경로가 재발급이다.
      -->
      <BaseButton variant="secondary" :disabled="reissuing" @click="confirmOpen = true">
        QR 재발급
      </BaseButton>

      <BaseModal :open="confirmOpen" title="QR을 새로 발급할까요?" @close="confirmOpen = false">
        <p class="confirm-text">
          지금 매장에 붙여둔 QR은 <strong>즉시 사용할 수 없게 됩니다.</strong> 새 QR을 다시 출력해
          교체해주세요.
        </p>
        <template #footer>
          <BaseButton variant="ghost" :disabled="reissuing" @click="confirmOpen = false">
            취소
          </BaseButton>
          <BaseButton variant="danger" :disabled="reissuing" @click="confirmReissue">
            {{ reissuing ? '발급 중…' : '재발급' }}
          </BaseButton>
        </template>
      </BaseModal>
    </template>
  </div>
</template>

<style scoped>
.qr-screen {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-lg);
}

.head {
  text-align: center;
}
.title {
  font-size: var(--text-xl);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}
.desc {
  margin-top: var(--space-xs);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}

/* 내용이 QR 하나뿐이라 aspect-ratio 가 실제로 정사각형을 만든다. 토큰이 함께 있던
   동안에는 텍스트가 세 줄로 늘어나 상자가 260x281 로 어긋나 있었다.
   QR 은 상자 안쪽 폭을 그대로 채운다 — 남는 여백 없이 상자와 한 덩어리로 읽힌다. */
.qr-box {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  max-width: 260px;
  aspect-ratio: 1;
  padding: var(--space-lg);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  text-align: center;
}

/* 512px 로 그린 캔버스를 축소해 표시한다(인쇄·확대 시 선명도 확보).
   여기 값이 실제로 먹으려면 draw() 가 qrcode 의 인라인 style 을 지워야 한다 —
   지우지 않으면 이 규칙은 인라인 512px 에 밀린다. */
.qr-canvas {
  display: block;
  width: 100%;
  height: auto;
  border-radius: var(--radius-sm);
}
.qr-placeholder {
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}

.confirm-text {
  font-size: var(--text-sm);
  color: var(--color-text);
  line-height: 1.6;
}
</style>
