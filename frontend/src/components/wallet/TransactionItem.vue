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

// FUNDING·WITHDRAWAL은 근무와 무관해 workTitle이 없다. 2행이 비어 보이지 않도록
// 근무제목 자리에 거래 유형을 그대로 보여준다(#492).
const NO_WORK_TITLE_LABELS = {
  FUNDING: '안심지갑 충전',
  WITHDRAWAL: '안심지갑 출금'
}

const statusMeta = computed(() => STATUS_META[props.tx.displayStatus] ?? STATUS_META.COMPLETED)

// 거래 Type으로 부호를 추정하면 WORKER의 ESCROW_RELEASE와 ADJUSTMENT를 오표시한다.
const isCredit = computed(() => props.tx.direction === 'CREDIT')
const amountText = computed(() => formatSignedKRW(props.tx.amount, props.tx.direction))
// ESCROW_RELEASE는 "정산 지급"(CREDIT)/"알바생 지급"(DEBIT)으로 갈렸었지만, 실제로는
// 사장님 화면엔 알바생에게 나간 지급만, 알바생 화면엔 자신이 받은 지급만 보여 한 화면에
// 두 라벨이 같이 나올 일이 없다. 라벨을 "지급" 하나로 통일하고, 배지 색(owner/worker)으로만
// 구분한다.
const typeLabel = computed(() => TYPE_LABELS[props.tx.type] || '지갑 거래')
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
// 근무와 무관한 거래(충전·출금)는 workTitle 대신 거래 유형 자체를 표시한다(#492).
const topLine = computed(() => props.tx.workTitle ?? NO_WORK_TITLE_LABELS[props.tx.type] ?? '')
const bottomLine = computed(() => {
  const time = formatDateTime(props.tx.createdAt)
  return props.tx.workplaceName ? `${time} | ${props.tx.workplaceName}` : time
})
</script>

<template>
  <li class="tx">
    <p class="date">{{ bottomLine }}</p>

    <div class="row-type">
      <span class="type-badge" :class="`type-badge--${badgeTone}`">{{ typeLabel }}</span>
      <p v-if="topLine" class="desc">{{ topLine }}</p>
    </div>

    <div class="row-amount">
      <span class="status" :style="{ color: statusMeta.color }">
        <component :is="statusMeta.icon" :size="12" />
        {{ statusMeta.label }}
      </span>
      <p class="amount" :class="{ 'is-credit': isCredit }">{{ amountText }}</p>
    </div>
  </li>
</template>

<style scoped>
.tx {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  padding: var(--space-lg) 0;
  border-bottom: 1px solid var(--color-border);
}

.row-type {
  display: flex;
  align-items: center;
  gap: var(--space-md);
}

/* 문서함 type-badge(WorkerDocumentsView:610)와 같은 pill 모양·기준. width를 고정하면
   "출금 환불"/"예치 환불"/"잔액 조정" 같은 4글자 라벨이 nowrap과 함께 pill 밖으로
   삐져나올 수 있어(#487 리뷰) min-width로 바꿔 내용에 따라 늘어나게 한다. */
.type-badge {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  min-width: 76px;
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

/* .type-badge 기본값과 같은 값이지만, 'neutral'이 실제 셀렉터로 존재해야 나중에 이
   톤만 따로 조정하거나 기본값이 다른 이유로 바뀌어도 서로 영향을 주지 않는다(#487 리뷰). */
.type-badge--neutral {
  background: var(--color-bg);
  color: var(--color-text-sub);
}

.desc {
  flex: 1;
  min-width: 0;
  font-size: var(--text-lg);
  font-weight: var(--weight-medium);
  color: var(--color-text);
  word-break: break-word;
}

.date {
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}

.row-amount {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-xs);
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
  font-size: var(--text-sm);
}
</style>
