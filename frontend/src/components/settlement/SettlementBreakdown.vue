<script setup>
import { computed, useId } from 'vue'

import { formatKRW, formatSeoulDateTime } from '@/utils/format'

const props = defineProps({
  settlement: { type: Object, required: true }
})
const titleId = useId()

const CALCULATION_REASON_LABELS = Object.freeze({
  CHECKED_OUT: '출퇴근 기록 기준',
  NO_SHOW: '노쇼 판정 기준',
  CHECK_OUT_MISSING: '퇴근 누락 판정 기준',
  LEGACY: '기존 정산 기록'
})

const hasSnapshot = computed(
  () =>
    Number.isSafeInteger(props.settlement.workerPaidAmount) &&
    Number.isSafeInteger(props.settlement.ownerRefundAmount) &&
    props.settlement.calculatedAt != null
)

const calculationReasonLabel = computed(
  () => CALCULATION_REASON_LABELS[props.settlement.calculationReason] ?? '저장된 정산 기록'
)

function hasMinutes(value) {
  return Number.isSafeInteger(value) && value >= 0
}
</script>

<template>
  <section class="settlement-breakdown" :aria-labelledby="titleId">
    <h2 :id="titleId" class="title">정산 계산</h2>

    <p v-if="!hasSnapshot" class="pending">
      근무 종료 결과가 확정되면 서버가 계산한 지급액과 환불액을 표시합니다.
    </p>

    <template v-else>
      <dl class="details">
        <div class="row">
          <dt>약정 일급</dt>
          <dd>{{ formatKRW(settlement.amount) }}</dd>
        </div>
        <div v-if="hasMinutes(settlement.deductionBaseMinutes)" class="row">
          <dt>차감 기준 시간</dt>
          <dd>{{ settlement.deductionBaseMinutes }}분</dd>
        </div>
        <div v-if="hasMinutes(settlement.lateMinutes)" class="row">
          <dt>지각</dt>
          <dd>{{ settlement.lateMinutes }}분</dd>
        </div>
        <div v-if="hasMinutes(settlement.earlyLeaveMinutes)" class="row">
          <dt>조퇴</dt>
          <dd>{{ settlement.earlyLeaveMinutes }}분</dd>
        </div>
        <div class="row deduction">
          <dt>근태 차감액</dt>
          <dd>{{ formatKRW(settlement.deductionAmount) }}</dd>
        </div>
        <div class="row result">
          <dt>알바생 지급액</dt>
          <dd>{{ formatKRW(settlement.workerPaidAmount) }}</dd>
        </div>
        <div class="row result">
          <dt>사장님 환불액</dt>
          <dd>{{ formatKRW(settlement.ownerRefundAmount) }}</dd>
        </div>
      </dl>

      <p class="basis">
        {{ calculationReasonLabel }} · {{ formatSeoulDateTime(settlement.calculatedAt) }} 산정
      </p>
    </template>
  </section>
</template>

<style scoped>
.settlement-breakdown {
  margin-top: var(--space-md);
  padding: var(--space-lg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}
.title {
  font-size: var(--text-lg);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}
.pending,
.basis {
  margin-top: var(--space-sm);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
  line-height: 1.5;
}
.details {
  margin-top: var(--space-sm);
}
.row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-md);
  padding: var(--space-xs) 0;
  font-size: var(--text-md);
}
.row dt {
  color: var(--color-text-sub);
}
.row dd {
  color: var(--color-text);
  text-align: right;
}
.deduction {
  margin-top: var(--space-xs);
  padding-top: var(--space-sm);
  border-top: 1px solid var(--color-border);
}
.result dd {
  font-weight: var(--weight-bold);
}
</style>
