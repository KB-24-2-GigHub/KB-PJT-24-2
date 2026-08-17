<script setup>
/**
 * 출금 실행 전 확인 모달 — 사장/알바생 출금 화면 공용.
 * 입금 은행·계좌번호·금액을 다시 보여주고 확인받는다.
 * 예금주명은 폼에서 받지 않으므로 표시하지 않는다.
 */
import BaseButton from '@/components/common/BaseButton.vue'
import BaseModal from '@/components/common/BaseModal.vue'
import { formatKRW } from '@/utils/format'

defineProps({
  open: { type: Boolean, default: false },
  bankName: { type: String, default: '' },
  accountNo: { type: String, default: '' },
  amount: { type: Number, default: 0 },
  // 확인 버튼 색상(사장=owner, 알바생=worker)
  variant: { type: String, default: 'owner' },
  submitting: { type: Boolean, default: false }
})

const emit = defineEmits(['confirm', 'close'])
</script>

<template>
  <BaseModal :open="open" title="출금 확인" @close="emit('close')">
    <dl class="summary">
      <div class="detail-row">
        <dt>입금 계좌</dt>
        <dd>{{ bankName }} {{ accountNo }}</dd>
      </div>
      <div class="detail-row">
        <dt>출금 금액</dt>
        <dd class="amount">{{ formatKRW(amount) }}</dd>
      </div>
    </dl>
    <p class="ask">위 계좌로 출금하시겠습니까?</p>

    <template #footer>
      <BaseButton
        class="modal-btn"
        variant="secondary"
        :disabled="submitting"
        @click="emit('close')"
      >
        취소
      </BaseButton>
      <BaseButton
        class="modal-btn"
        :variant="variant"
        :disabled="submitting"
        @click="emit('confirm')"
      >
        {{ submitting ? '처리 중…' : '출금하기' }}
      </BaseButton>
    </template>
  </BaseModal>
</template>

<style scoped>
.summary {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  padding: var(--space-md) var(--space-lg);
  background: var(--color-bg);
  border-radius: var(--radius-sm);
}
.detail-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-md);
}
.detail-row dt {
  flex-shrink: 0;
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.detail-row dd {
  text-align: right;
  font-size: var(--text-md);
  font-weight: var(--weight-medium);
  color: var(--color-text);
  word-break: break-all;
}
.detail-row dd.amount {
  font-size: var(--text-lg);
  font-weight: var(--weight-bold);
}
.ask {
  margin-top: var(--space-lg);
  text-align: center;
  font-size: var(--text-md);
  color: var(--color-text);
}
/* 취소/출금 버튼을 균등 폭으로 나란히 배치 */
.modal-btn {
  flex: 1;
}
</style>
