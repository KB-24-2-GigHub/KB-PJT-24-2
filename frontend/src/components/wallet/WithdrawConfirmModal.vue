<script setup>
/**
 * 출금 실행 전 확인 모달 — 사장/알바생 출금 화면 공용.
 * 입금 은행·계좌번호·금액을 다시 보여주고 확인받는다.
 * 예금주명은 폼에서 받지 않으므로 표시하지 않는다.
 *
 * 지갑 비밀번호(PIN)는 이 화면에서만 입력받는 화면 단 게이트다. 출금 API
 * (`POST /wallet/withdrawal-requests`)는 `{bankCode, accountNo, amount}` 세 필드만
 * 받고 PIN 검증 계약이 없어(API_SPEC.md, DEC-IDEMPOTENCY-CLAIM-LIFECYCLE Fingerprint도
 * 이 세 필드 기준) 서버로 전송하지 않는다. 실제 서버 검증을 붙이려면 API 계약 변경이
 * 필요해 별도 승인된 spec-patch 범위다.
 */
import { computed, ref, watch } from 'vue'

import BaseButton from '@/components/common/BaseButton.vue'
import BaseModal from '@/components/common/BaseModal.vue'
import PinKeypad from '@/components/wallet/PinKeypad.vue'
import { formatKRW } from '@/utils/format'

const props = defineProps({
  open: { type: Boolean, default: false },
  bankName: { type: String, default: '' },
  accountNo: { type: String, default: '' },
  amount: { type: Number, default: 0 },
  // 확인 버튼 색상(사장=owner, 알바생=worker)
  variant: { type: String, default: 'owner' },
  submitting: { type: Boolean, default: false }
})

const emit = defineEmits(['confirm', 'close'])

const pin = ref('')
const pinValid = computed(() => /^\d{4}$/.test(pin.value))

// 모달을 닫으면(취소·출금 완료) 다음에 열 때 새로 입력하도록 비운다.
watch(
  () => props.open,
  (isOpen) => {
    if (!isOpen) pin.value = ''
  }
)

function onConfirm() {
  if (!pinValid.value || props.submitting) return
  emit('confirm')
}
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

    <PinKeypad v-model="pin" class="pin-field" label="지갑 비밀번호" />

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
        :disabled="submitting || !pinValid"
        @click="onConfirm"
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
.pin-field {
  margin-top: var(--space-lg);
}
/* 취소/출금 버튼을 균등 폭으로 나란히 배치 */
.modal-btn {
  flex: 1;
}
</style>
