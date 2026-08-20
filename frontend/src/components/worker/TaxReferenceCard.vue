<script setup>
import { formatKRW } from '@/utils/format'

defineProps({
  taxReference: { type: Object, required: true }
})
</script>

<template>
  <section class="tax-reference" aria-label="세금 참고 정보">
    <h2 class="title">예상 세액(참고)</h2>
    <p class="tax-amount">{{ formatKRW(taxReference.estimatedTaxAmount) }}</p>
    <p class="after-tax">
      세금을 고려하면 {{ formatKRW(taxReference.estimatedAfterTaxAmount) }}일 수 있어요.
    </p>
    <p class="basis">최종 지급액 {{ formatKRW(taxReference.basisAmount) }} 기준</p>
    <p class="notice">GigHub는 실제 지급액에서 세금을 차감하지 않습니다.</p>
  </section>
</template>

<style scoped>
.tax-reference {
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
.tax-amount {
  margin-top: var(--space-sm);
  font-size: var(--text-xl);
  font-weight: var(--weight-bold);
  color: var(--color-worker);
}
.after-tax,
.basis,
.notice {
  margin-top: var(--space-xs);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
  line-height: 1.5;
}
.notice {
  margin-top: var(--space-sm);
  color: var(--color-text);
  font-weight: var(--weight-medium);
}
</style>
