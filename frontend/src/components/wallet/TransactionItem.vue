<script setup>
import { CircleCheck, CircleX, Clock, RotateCcw } from 'lucide-vue-next'
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
// 배지 색은 문서함 type-badge(계약서=owner, 보건증=worker)와 같은 은은한 배경+글자색 톤을
// 거래 유형별 의미에 맞춰 고른다: 충전=success(잔액 증가), 지급 2종=owner/worker(각각
// 정산·알바생 몫), 출금=danger(잔액 유출). 예치는 warning(주황)이 worker(앰버)와
// 색상환에서 너무 가까워 혼동되므로 "보류·잠금" 의미에 맞는 neutral(회색)로 대비를 준다.
// 환불·조정도 잦지 않은 예외 처리라 같은 neutral(.type-badge 기본값)을 쓴다.
const badgeTone = computed(() => {
  if (props.tx.type === 'ESCROW_RELEASE') {
    return props.tx.direction === 'DEBIT' ? 'worker' : 'owner'
  }
  switch (props.tx.type) {
    case 'FUNDING':
      return 'success'
    case 'WITHDRAWAL':
      return 'danger'
    default:
      return 'neutral'
  }
})
// 거래 유형은 왼쪽 pill(type-badge)로 이미 보여주므로 본문 1행은 근무명만 남긴다.
// 근무와 무관한 거래(충전·출금 등)는 workTitle 이 없어 1행 자체를 비운다(v-if로 숨김).
const topLine = computed(() => props.tx.workTitle ?? '')
const bottomLine = computed(() => {
  const time = formatDateTime(props.tx.createdAt)
  return props.tx.workplaceName ? `${time} | ${props.tx.workplaceName}` : time
})
</script>

<template>
  <li class="tx">
    <span class="type-badge" :class="`type-badge--${badgeTone}`">{{ typeLabel }}</span>

    <div class="body">
      <p v-if="topLine" class="desc">{{ topLine }}</p>
      <p class="date">{{ bottomLine }}</p>
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

/* 문서함 type-badge(WorkerDocumentsView)와 같은 pill 모양. 너비는 가장 긴 라벨인
   "알바생 지급" 기준으로 고정해 8종 라벨 크기를 통일한다. */
.type-badge {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 84px;
  padding: var(--space-xs) var(--space-sm);
  border-radius: var(--radius-pill);
  background: var(--color-bg);
  color: var(--color-text-sub);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  white-space: nowrap;
}

.type-badge--success {
  background: var(--color-success-bg);
  color: var(--color-success);
}

.type-badge--danger {
  background: var(--color-danger-bg);
  color: var(--color-danger);
}

.type-badge--owner {
  background: var(--color-owner-weak);
  color: var(--color-owner);
}

.type-badge--worker {
  background: var(--color-worker-weak);
  color: var(--color-worker);
}

.body {
  flex: 1;
  min-width: 0;
}

.desc {
  font-size: var(--text-lg);
  font-weight: var(--weight-medium);
  color: var(--color-text);
  word-break: break-word;
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
