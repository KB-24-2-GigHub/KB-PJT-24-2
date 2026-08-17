<script setup>
import { Info } from 'lucide-vue-next'
import { computed, onMounted, onUnmounted, ref, useId } from 'vue'

import { useEarningTick } from '@/composables/useEarningTick'
import { calcDailyTax } from '@/utils/earning'
import { formatKRW } from '@/utils/format'

const props = defineProps({
  earning: { type: Object, required: true },
  workCase: { type: Object, required: true }
})

// 적립액·진행률은 근무 시작 시각 기준으로 1분마다 다시 계산한다(표시 전용 추정치).
const { elapsedPay, progressRatio } = useEarningTick(
  computed(() => props.earning),
  computed(() => props.workCase)
)

// 예상 실수령액은 일급 전액 기준이라 경과 시간과 무관하게 고정이다 — 서버 값이 있으면 그것을 쓰고,
// 없을 때만 calcDailyTax 로 폴백한다(참조 구현). elapsedPay/progressRatio 와 달리 서버 폴백이 있는 이유는
// 이 값이 시간에 따라 변하지 않는 순수 계산값이라 "stale" 문제가 없기 때문이다.
const tax = computed(() => calcDailyTax(props.earning.agreedWage))

const expectedNet = computed(() =>
  Number.isFinite(props.earning.expectedNetAmount)
    ? props.earning.expectedNetAmount
    : tax.value.expectedNetAmount
)

// 공제액은 화면에 실제로 보여주는 실수령액에서 역산한다 — 서버 값을 쓸 때도 두 줄이 어긋나지 않는다.
const deducted = computed(() => Math.max(0, props.earning.agreedWage - expectedNet.value))

const progress = computed(() => Math.min(1, Math.max(0, progressRatio.value)))

const taxNote = computed(() =>
  deducted.value > 0 ? `(세금 ${formatKRW(deducted.value)} 공제)` : '(세금 공제 없음)'
)

/* ---- 안내 팝오버 (호버 아님 — 클릭 토글) ---- */
const rootEl = ref(null)
const infoOpen = ref(false)
const infoId = useId()

function toggleInfo() {
  infoOpen.value = !infoOpen.value
}

function onDocumentClick(e) {
  if (!infoOpen.value) return
  if (!rootEl.value?.contains(e.target)) infoOpen.value = false
}

function onKeydown(e) {
  if (e.key === 'Escape') infoOpen.value = false
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
  document.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <section ref="rootEl" class="earning-card">
    <div class="head-row">
      <header class="head">
        <h2 class="title">근무 경과 예상금액</h2>

        <button
          type="button"
          class="info"
          aria-label="예상금액 안내"
          :aria-expanded="infoOpen"
          :aria-controls="infoId"
          @click="toggleInfo"
        >
          <Info :size="14" />
        </button>
      </header>

      <div v-if="infoOpen" :id="infoId" class="info-popover" role="note">
        <p>
          표시 금액은 근무 시작 시각부터 경과한 시간에 비례해 1분마다 갱신되는 참고용 예상치예요.
        </p>
        <p>지갑 잔액·예치금·실제 지급액과는 무관하며, 이 값이 실제 정산 금액을 결정하지 않아요.</p>
        <p>휴게시간·지각 등 특이사항이 있으면 실제 지급액은 달라질 수 있어요.</p>
        <p>
          예상 실수령액은 일용직 원천징수 기준(일당 15만원 초과분에 소득세 2.7%, 지방소득세
          0.27%)으로 계산한 값입니다.
        </p>
      </div>
    </div>

    <p class="amount">{{ formatKRW(elapsedPay) }}</p>
    <p class="sub">일급 {{ formatKRW(earning.agreedWage) }} 기준 근무 경과 참고값</p>

    <p class="net">
      예상 실수령액 <strong>{{ formatKRW(expectedNet) }}</strong>
      <span class="net-tax">{{ taxNote }}</span>
    </p>

    <div
      class="bar"
      role="progressbar"
      :aria-valuenow="Math.round(progress * 100)"
      aria-valuemin="0"
      aria-valuemax="100"
    >
      <div class="seg" :style="{ width: progress * 100 + '%' }"></div>
    </div>

    <!-- 지각은 표시(뱃지)로만 — 임금 차감 없음 -->
    <p v-if="earning.isLate" class="late-note">지각 {{ earning.lateMinutes }}분</p>
  </section>
</template>

<style scoped>
.earning-card {
  margin-top: var(--space-md);
  padding: var(--space-lg);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}

.head-row {
  position: relative;
}

.head {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.title {
  font-size: var(--text-lg);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}

.info {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: var(--radius-pill);
  background: var(--color-worker-weak);
  color: var(--color-worker);
}

/* 헤더 폭(=카드 콘텐츠 폭)에 물려 뜬다 — 어떤 화면 폭에서도 카드를 넘지 않고,
   아래 내용을 밀어내지 않고 덮는다. */
.info-popover {
  position: absolute;
  z-index: 1;
  top: calc(100% + var(--space-sm));
  left: 0;
  right: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  padding: var(--space-md);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-card);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
  line-height: 1.5;
}

.amount {
  margin-top: var(--space-sm);
  font-size: var(--text-2xl);
  font-weight: var(--weight-bold);
  color: var(--color-worker);
}

.sub {
  margin-top: var(--space-xs);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}

.net {
  margin-top: var(--space-xs);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}

.net strong {
  font-weight: var(--weight-bold);
  color: var(--color-text);
}

.net-tax {
  margin-left: var(--space-xs);
}

.bar {
  display: flex;
  height: 10px;
  margin-top: var(--space-md);
  border-radius: var(--radius-pill);
  background: var(--color-bg);
  overflow: hidden;
}

.seg {
  background: var(--color-worker);
}

.late-note {
  margin-top: var(--space-sm);
  font-size: var(--text-sm);
  color: var(--color-warning);
}
</style>
