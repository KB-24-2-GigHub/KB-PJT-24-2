<script setup>
import { ArrowUpRight, CircleCheck, CircleX, Clock, Lock, Plus, RotateCcw } from 'lucide-vue-next'
import { computed } from 'vue'

import { formatDateTime, formatSignedKRW } from '@/utils/format'

const props = defineProps({
  tx: { type: Object, required: true }
})

const TYPE_LABELS = {
  FUNDING: '충전',
  ESCROW_HOLD: '예치',
  ESCROW_RELEASE: '지급',
  ESCROW_REFUND: '예치 환불',
  WITHDRAWAL: '출금',
  WITHDRAWAL_REFUND: '출금 환불',
  ADJUSTMENT: '잔액 조정'
}

const STATUS_META = {
  PENDING: { label: '처리 중', color: 'var(--color-warning)', icon: Clock },
  COMPLETED: { label: '완료', color: 'var(--color-success)', icon: CircleCheck },
  FAILED: { label: '실패', color: 'var(--color-danger)', icon: CircleX },
  REFUNDED: { label: '환불 완료', color: 'var(--color-text-sub)', icon: RotateCcw }
}

const typeIcon = computed(() => {
  switch (props.tx.type) {
    case 'FUNDING':
      return Plus
    case 'WITHDRAWAL':
      return ArrowUpRight
    case 'ESCROW_REFUND':
    case 'WITHDRAWAL_REFUND':
      return RotateCcw
    case 'ESCROW_RELEASE':
      return CircleCheck
    case 'ESCROW_HOLD':
      return Lock
    default:
      return CircleCheck
  }
})

const statusMeta = computed(() => STATUS_META[props.tx.displayStatus] ?? STATUS_META.COMPLETED)

// 거래 Type으로 부호를 추정하면 WORKER의 ESCROW_RELEASE와 ADJUSTMENT를 오표시한다.
const isCredit = computed(() => props.tx.direction === 'CREDIT')
const amountText = computed(() => formatSignedKRW(props.tx.amount, props.tx.direction))
const typeLabel = computed(() => {
  if (props.tx.type === 'ESCROW_RELEASE') {
    return props.tx.direction === 'DEBIT' ? '알바생 지급' : '정산 지급'
  }
  return TYPE_LABELS[props.tx.type] || '지갑 거래'
})
const description = computed(() => {
  return [typeLabel.value, props.tx.workTitle, props.tx.workplaceName].filter(Boolean).join(' · ')
})
</script>

<template>
  <li class="tx">
    <span class="icon" :class="{ 'is-credit': isCredit }">
      <component :is="typeIcon" :size="20" />
    </span>

    <div class="body">
      <p class="desc">{{ description }}</p>
      <p class="date">{{ formatDateTime(tx.createdAt) }}</p>
    </div>

    <div class="right">
      <p class="amount" :class="{ 'is-credit': isCredit }">{{ amountText }}</p>
      <span class="status" :style="{ color: statusMeta.color }">
        <component :is="statusMeta.icon" :size="12" />
        {{ statusMeta.label }}
      </span>
    </div>
  </li>
</template>

<style scoped>
.tx {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  padding: var(--space-lg) 0;
  border-bottom: 1px solid var(--color-border);
}

.icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  flex-shrink: 0;
  border-radius: var(--radius-pill);
  background: var(--color-bg);
  color: var(--color-text-sub);
}

.icon.is-credit {
  color: var(--color-owner);
}

.body {
  flex: 1;
  min-width: 0;
}

.desc {
  overflow: hidden;
  font-size: var(--text-lg);
  font-weight: var(--weight-medium);
  color: var(--color-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.date {
  margin-top: var(--space-xs);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}

.right {
  flex-shrink: 0;
  text-align: right;
}

.amount {
  font-size: var(--text-lg);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}

.amount.is-credit {
  color: var(--color-owner);
}

.status {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  margin-top: var(--space-xs);
  font-size: var(--text-sm);
}
</style>
