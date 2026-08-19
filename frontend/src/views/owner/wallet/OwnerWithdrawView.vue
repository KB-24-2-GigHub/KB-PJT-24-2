<script setup>
/**
 * [B] 사장 출금  ·  /owner/wallet/withdraw  ·  OWNER
 * 입금 은행·계좌번호·금액 지정. 가용 잔액 내에서만(초과 시 서버 409).
 * 연계 API: POST /wallet/withdrawal-requests  →  @/services/wallet
 * 공통: BankSelect(은행) · AppField(계좌) · WalletAmountField · 승인 계좌/금액 검증
 */
import { storeToRefs } from 'pinia'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import AppBackHeader from '@/components/common/AppBackHeader.vue'
import AppField from '@/components/common/AppField.vue'
import BaseButton from '@/components/common/BaseButton.vue'
import BankSelect from '@/components/wallet/BankSelect.vue'
import WalletAmountField from '@/components/wallet/WalletAmountField.vue'
import WithdrawConfirmModal from '@/components/wallet/WithdrawConfirmModal.vue'
import { newIdempotencyKey } from '@/services/http'
import { withdrawWallet } from '@/services/wallet'
import { useUiStore } from '@/stores/ui'
import { useWalletStore } from '@/stores/wallet'
import { findBank } from '@/utils/constants'
import { formatKRW } from '@/utils/format'
import { bankAccountRule, isWalletAmount, normalizeBankAccountNo } from '@/utils/validators'

const router = useRouter()
const ui = useUiStore()
const walletStore = useWalletStore()
const { availableBalance } = storeToRefs(walletStore)

const bankCode = ref('')
const accountNo = ref('')
const amount = ref('')
const accountError = ref('')
const amountError = ref('')
const submitting = ref(false)
const confirmOpen = ref(false)
// 확인 모달을 여는 시점에만 새로 발급하고, 같은 모달 안의 수동 재시도는 이 키를 재사용한다.
const idempotencyKey = ref(newIdempotencyKey())

const bankName = computed(() => findBank(bankCode.value)?.name ?? '')

onMounted(() => {
  // 전액 버튼·잔액 초과 가드에 필요하므로 항상 최신 잔액을 로드한다.
  walletStore.loadWallet()
})

const accountCheck = computed(() => bankAccountRule(accountNo.value))

/** 금액: 양의 정수 + 가용 잔액 이내(서버가 최종 검증, 여기선 UX 가드) */
const amountCheck = computed(() => {
  const base = isWalletAmount(amount.value)
  if (!base.valid) return base
  if (Number(amount.value) > availableBalance.value) {
    return { valid: false, message: '가용 잔액을 초과했습니다.' }
  }
  return { valid: true, message: '' }
})
const accountFieldError = computed(() =>
  accountNo.value && !accountCheck.value.valid ? accountCheck.value.message : accountError.value
)
const amountFieldError = computed(() =>
  amount.value && !amountCheck.value.valid ? amountCheck.value.message : amountError.value
)

const canSubmit = computed(
  () => !!bankCode.value && accountCheck.value.valid && amountCheck.value.valid && !submitting.value
)

// 출금하기 클릭 → 검증 후 확인 모달을 연다(실제 출금은 모달 확인 시).
function onRequestConfirm() {
  if (!bankCode.value) {
    ui.toast('은행을 선택해주세요.', { type: 'warning' })
    return
  }
  accountError.value = accountCheck.value.valid ? '' : accountCheck.value.message
  amountError.value = amountCheck.value.valid ? '' : amountCheck.value.message
  if (accountError.value || amountError.value) return
  idempotencyKey.value = newIdempotencyKey()
  confirmOpen.value = true
}

async function onSubmit() {
  submitting.value = true
  ui.startLoading('출금을 처리하고 있어요…')
  try {
    await withdrawWallet(
      {
        bankCode: bankCode.value,
        accountNo: normalizeBankAccountNo(accountNo.value),
        amount: Number(amount.value)
      },
      { idempotencyKey: idempotencyKey.value }
    )
    await walletStore.loadWallet()
    confirmOpen.value = false
    ui.toast(`${formatKRW(Number(amount.value))} 출금 신청이 완료되었습니다.`, { type: 'success' })
    router.back()
  } catch {
    ui.toast('출금에 실패했습니다. 잔액을 확인해주세요.', { type: 'danger' })
  } finally {
    submitting.value = false
    ui.stopLoading()
  }
}
</script>

<template>
  <div class="sub-page">
    <AppBackHeader title="출금" />
    <main class="screen-body">
      <p class="balance-line">
        출금 가능 금액 <strong>{{ formatKRW(availableBalance) }}</strong>
      </p>

      <BankSelect v-model="bankCode" label="입금 은행" />

      <AppField
        :model-value="accountNo"
        label="계좌번호"
        type="tel"
        placeholder="계좌번호 10~14자리"
        maxlength="20"
        :error="accountFieldError"
        @update:model-value="(v) => (accountNo = v)"
      />

      <WalletAmountField
        v-model="amount"
        label="출금 금액"
        :error="amountFieldError"
        :fill-amount="availableBalance"
        :max="availableBalance"
      />

      <BaseButton
        class="submit"
        variant="owner"
        size="lg"
        block
        :disabled="!canSubmit"
        @click="onRequestConfirm"
      >
        출금하기
      </BaseButton>
    </main>

    <WithdrawConfirmModal
      :open="confirmOpen"
      :bank-name="bankName"
      :account-no="accountNo"
      :amount="Number(amount) || 0"
      variant="owner"
      :submitting="submitting"
      @confirm="onSubmit"
      @close="confirmOpen = false"
    />
  </div>
</template>

<style scoped>
.screen-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-xl);
  padding: var(--space-lg);
}
.balance-line {
  font-size: var(--text-md);
  color: var(--color-text-sub);
}
.balance-line strong {
  margin-left: var(--space-xs);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}
.submit {
  margin-top: var(--space-sm);
}
</style>
