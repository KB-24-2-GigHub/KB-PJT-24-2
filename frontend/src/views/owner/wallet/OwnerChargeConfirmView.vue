<script setup>
/**
 * OWNER 충전 최종 확인 화면.
 * 계좌번호와 멱등키는 현재 화면 메모리에만 두고, PIN은 요청 종료·화면 이탈 즉시 폐기한다.
 */
import { computed, onBeforeMount, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'

import AppBackHeader from '@/components/common/AppBackHeader.vue'
import BaseButton from '@/components/common/BaseButton.vue'
import PinKeypad from '@/components/wallet/PinKeypad.vue'
import { newIdempotencyKey } from '@/services/http'
import { chargeWallet } from '@/services/wallet'
import { useUiStore } from '@/stores/ui'
import { useWalletStore } from '@/stores/wallet'
import { useWalletFundingStore } from '@/stores/walletFunding'
import { findBank } from '@/utils/constants'
import { formatKRW } from '@/utils/format'

const router = useRouter()
const ui = useUiStore()
const walletStore = useWalletStore()
const fundingStore = useWalletFundingStore()

const pin = ref('')
const pinError = ref('')
const requestError = ref('')
const submitting = ref(false)
const logoFailed = ref(false)
// 네트워크 결과가 불확실한 수동 재시도까지 같은 요청 의도로 묶는다.
const idempotencyKey = ref(newIdempotencyKey())

const draft = computed(() => fundingStore.draft)
const bank = computed(() => findBank(draft.value?.bankCode))
const maskedAccountNo = computed(() => {
  const value = draft.value?.accountNo ?? ''
  if (value.length <= 4) return value
  return `${'•'.repeat(value.length - 4)}${value.slice(-4)}`
})
const pinValid = computed(() => /^\d{4}$/.test(pin.value))
const canSubmit = computed(
  () => !!draft.value && !!bank.value && pinValid.value && !submitting.value
)

onBeforeMount(() => {
  if (draft.value && bank.value) return
  fundingStore.clearDraft()
  ui.toast('충전 정보를 다시 입력해주세요.', { type: 'warning' })
  router.replace({ name: 'owner-charge' })
})

onBeforeUnmount(() => {
  clearPin()
  fundingStore.clearDraft()
})

function updatePin(value) {
  pin.value = value
  pinError.value = ''
  requestError.value = ''
}

function clearPin() {
  pin.value = ''
}

function fundingErrorInfo(error) {
  const status = error?.response?.status
  const code = error?.code ?? error?.response?.data?.code
  const message = error?.response?.data?.message

  if (status === undefined || status >= 500) {
    return {
      message: '처리 결과를 확인하지 못했습니다. PIN을 다시 입력해 같은 요청으로 재시도해주세요.',
      type: 'danger'
    }
  }
  if (status === 401 || code === 'AUTH_REQUIRED') {
    return { message: '로그인이 만료되었습니다. 다시 로그인해주세요.', type: 'warning' }
  }
  if (status === 403 && message === '계좌를 사용할 수 없습니다.') {
    return { message, type: 'warning' }
  }
  if (status === 403) {
    return {
      message: '보안 확인에 실패했습니다. 화면을 새로고침한 뒤 다시 시도해주세요.',
      type: 'warning'
    }
  }
  if (status === 409) {
    const approvedMessages = [
      '계좌 잔액이 부족합니다.',
      '요청이 충돌했습니다. 잠시 후 다시 시도해 주세요.'
    ]
    return {
      message: approvedMessages.includes(message)
        ? message
        : '요청이 충돌했습니다. 잠시 후 다시 시도해주세요.',
      type: 'warning'
    }
  }
  if (status === 400 || code === 'VALIDATION_ERROR') {
    return { message: '입력값을 확인해 주세요. 충전 정보를 다시 입력해주세요.', type: 'warning' }
  }
  return { message: '충전에 실패했습니다. 잠시 후 다시 시도해주세요.', type: 'danger' }
}

async function onSubmit() {
  if (!pinValid.value) {
    pinError.value = 'PIN은 숫자 4자리여야 합니다.'
    return
  }
  if (!draft.value || !bank.value || submitting.value) return

  submitting.value = true
  ui.startLoading('충전을 처리하고 있어요…')
  requestError.value = ''
  const payload = { ...draft.value, pin: pin.value }
  const submittedAmount = draft.value.amount

  try {
    // Body에는 승인된 네 필드만 넣고 내부 사용자·지갑·계좌 ID는 전송하지 않는다.
    await chargeWallet(payload, { idempotencyKey: idempotencyKey.value })
    clearPin()
    fundingStore.clearDraft()

    let refreshFailed = false
    try {
      await Promise.all([
        walletStore.loadWallet(),
        walletStore.loadTransactions({ ...walletStore.transactionQuery, page: 0 })
      ])
    } catch {
      refreshFailed = true
    }

    ui.toast(`${formatKRW(submittedAmount)} 충전이 완료되었습니다.`, { type: 'success' })
    if (refreshFailed) {
      ui.toast('최신 잔액과 거래내역은 홈에서 다시 불러와주세요.', { type: 'warning' })
    }
    await router.replace({ name: 'owner-home' })
  } catch (error) {
    clearPin()
    const info = fundingErrorInfo(error)
    requestError.value = info.message
    ui.toast(info.message, { type: info.type })
  } finally {
    submitting.value = false
    ui.stopLoading()
  }
}
</script>

<template>
  <div class="sub-page">
    <AppBackHeader title="이체 확인" />
    <main v-if="draft && bank" class="screen-body">
      <section class="summary" aria-label="충전 이체 정보">
        <div class="bank-row">
          <img
            v-if="bank.logo && !logoFailed"
            :src="bank.logo"
            :alt="`${bank.name} 로고`"
            class="bank-logo"
            @error="logoFailed = true"
          />
          <span v-else class="bank-dot" :style="{ background: bank.chip }" />
          <strong>{{ bank.name }}</strong>
        </div>
        <p class="account">{{ maskedAccountNo }}</p>
        <p class="amount">{{ formatKRW(draft.amount) }}</p>
        <p class="caption">위 계좌에서 지갑으로 충전합니다.</p>
      </section>

      <section class="pin-section">
        <PinKeypad
          class="pin-field"
          :model-value="pin"
          label="계좌 PIN"
          hint="Demo PIN은 0000입니다."
          :error="pinError"
          @update:model-value="updatePin"
        />
        <p v-if="requestError" class="request-error" role="alert">{{ requestError }}</p>
      </section>

      <BaseButton
        class="confirm"
        variant="owner"
        size="lg"
        block
        :disabled="!canSubmit"
        @click="onSubmit"
      >
        {{ submitting ? '처리 중…' : '충전 확인' }}
      </BaseButton>
    </main>
  </div>
</template>

<style scoped>
.screen-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-xl);
  padding: var(--space-lg);
}
.summary {
  padding: var(--space-xl);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  box-shadow: var(--shadow-card);
  text-align: center;
}
.bank-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-sm);
  color: var(--color-text);
}
.bank-logo {
  width: 28px;
  height: 28px;
  object-fit: contain;
}
.bank-dot {
  width: 18px;
  height: 18px;
  border-radius: var(--radius-pill);
}
.account {
  margin-top: var(--space-sm);
  color: var(--color-text-sub);
  letter-spacing: 0.08em;
}
.amount {
  margin-top: var(--space-lg);
  font-size: var(--text-2xl);
  font-weight: var(--weight-bold);
  color: var(--color-owner);
}
.caption {
  margin-top: var(--space-xs);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.pin-section {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}
.request-error {
  font-size: var(--text-sm);
  color: var(--color-danger);
}
.confirm {
  margin-top: var(--space-sm);
}
</style>
