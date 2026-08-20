<script setup>
import { Info } from 'lucide-vue-next'
import { computed, onMounted, onUnmounted, ref, useId } from 'vue'

import { useEarningTick } from '@/composables/useEarningTick'
import { formatKRW } from '@/utils/format'

const props = defineProps({
  earning: { type: Object, required: true },
  workCase: { type: Object, required: true }
})

// 적립액·진행률은 근무 시작 시각 기준으로 1분마다 다시 계산한다(표시 전용 추정치).
const { elapsedPay, progressRatio, lateRatio } = useEarningTick(
  computed(() => props.earning),
  computed(() => props.workCase)
)

// 지각(주황)과 근무 경과(노랑)를 막대 하나에 나란히 쌓아 보여준다. 체크인 전엔
// lateRatio만 실시간으로 늘고, 체크인하면 그 폭에서 고정된 채 옆으로 progressRatio가
// 새로 쌓인다(useEarningTick 참고).
const lateWidth = computed(() => Math.min(1, Math.max(0, lateRatio.value)))
const progressWidth = computed(() =>
  Math.min(1 - lateWidth.value, Math.max(0, progressRatio.value))
)
const totalWidth = computed(() => lateWidth.value + progressWidth.value)

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
      </div>
    </div>

    <p class="amount">{{ formatKRW(elapsedPay) }}</p>
    <p class="sub">일급 {{ formatKRW(earning.agreedWage) }} 기준 근무 경과 참고값</p>

    <div
      class="bar"
      role="progressbar"
      :aria-valuenow="Math.round(totalWidth * 100)"
      aria-valuemin="0"
      aria-valuemax="100"
    >
      <div class="seg-late" :style="{ width: lateWidth * 100 + '%' }"></div>
      <div class="seg-progress" :style="{ width: progressWidth * 100 + '%' }"></div>
    </div>

    <!-- 지각 시간은 참고 표시이며 실제 차감액은 서버 Snapshot이 확정한다. -->
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
   아래 내용을 밀어내지 않고 덮는다.

   아래가 아니라 **위로** 펼친다. 이 카드는 알바생 홈의 마지막 요소라 아래에 남는 공간이
   하단 탭바 높이뿐이고, 아래로 펼치면 마지막 문단이 탭바에 잘린다. 팝오버는 흐름 밖이라
   스크롤로 꺼내올 수도 없다. 위쪽에는 잔액 카드와 오늘의 일정 카드가 있어 늘 자리가 남는다.

   z-index 는 탭바(`--z-tabbar`)보다 위에 둔다 — `.head-row` 의 position:relative 는
   stacking context 를 만들지 않아(z-index:auto) 팝오버가 루트에서 탭바와 직접 겨룬다.
   max-height 는 문단이 늘어났을 때 위로 넘쳐 잘리는 것을 막는 상한이다. */
.info-popover {
  position: absolute;
  z-index: calc(var(--z-tabbar) + 1);
  bottom: calc(100% + var(--space-sm));
  left: 0;
  right: 0;
  max-height: 60vh;
  overflow-y: auto;
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

.bar {
  display: flex;
  height: 10px;
  margin-top: var(--space-md);
  border-radius: var(--radius-pill);
  background: var(--color-bg);
  overflow: hidden;
}

.seg-progress {
  background: var(--color-worker);
}

.seg-late {
  background: var(--color-late);
}

.late-note {
  margin-top: var(--space-sm);
  font-size: var(--text-sm);
  color: var(--color-late);
}
</style>
