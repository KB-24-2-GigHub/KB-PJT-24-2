<script setup>
/**
 * [B] 사장 홈(지갑)  ·  /owner/home  ·  OWNER  (탭 화면 — chrome 은 OwnerTabLayout)
 * 지갑 잔액·예치중(전 지점 합산, 지점 select 무관) + 충전·출금 + 송금상세 리스트.
 * 연계 API: GET /wallet · GET /wallet/transactions  →  @/services/wallet
 */
import { Info, Lock } from 'lucide-vue-next'
import { storeToRefs } from 'pinia'
import { onMounted, onUnmounted, ref, useId } from 'vue'
import { useRouter } from 'vue-router'

import TransactionFilterSheet from '@/components/wallet/TransactionFilterSheet.vue'
import TransactionList from '@/components/wallet/TransactionList.vue'
import WalletBalanceCard from '@/components/wallet/WalletBalanceCard.vue'
import { useWalletStore } from '@/stores/wallet'
import { useWorkplaceStore } from '@/stores/workplace'
import { formatKRW } from '@/utils/format'

const router = useRouter()
const walletStore = useWalletStore()
const workplaceStore = useWorkplaceStore()
const {
  availableBalance,
  lockedBalance,
  transactions,
  transactionPage,
  loading,
  transactionsLoading
} = storeToRefs(walletStore)
const { activeWorkplaces } = storeToRefs(workplaceStore)

const filterOpen = ref(false)
const appliedFilter = ref({}) // 현재 적용 중인 송금상세 필터(서버 파라미터)

// 예치중(에스크로) 안내 — 홈의 예치중 요약 옆 물음표 아이콘을 눌러 여는 팝오버 문구.
const HELD_TOOLTIP =
  '근무 계약 시 지급 예정 임금을 미리 안전하게 보관(에스크로)하는 금액입니다. 정산이 완료되면 알바생에게 지급되고, 노쇼 시 환불됩니다.'

onMounted(() => {
  walletStore.loadHome()
})

const onCharge = () => router.push('/owner/wallet/charge')
const onWithdraw = () => router.push('/owner/wallet/withdraw')
const onOpenFilter = () => (filterOpen.value = true)

// 필터는 서버 파라미터로만 전달 — 프론트에서 목록을 재계산하지 않는다.
function onApplyFilter(params) {
  appliedFilter.value = params
  walletStore.loadTransactions(params)
}

const onLoadMore = () => walletStore.loadNextTransactions()

/* ---- 예치중 안내 팝오버 (호버 아님 — 클릭 토글) ---- */
const heldSummaryEl = ref(null)
const heldInfoOpen = ref(false)
const heldInfoId = useId()

function toggleHeldInfo() {
  heldInfoOpen.value = !heldInfoOpen.value
}

function onDocumentClick(e) {
  if (!heldInfoOpen.value) return
  if (!heldSummaryEl.value?.contains(e.target)) heldInfoOpen.value = false
}

function onKeydown(e) {
  if (e.key === 'Escape') heldInfoOpen.value = false
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
  <div class="owner-home">
    <WalletBalanceCard
      :available-balance="availableBalance"
      @charge="onCharge"
      @withdraw="onWithdraw"
    />

    <div ref="heldSummaryEl" class="held-summary">
      <span class="held-label">
        <Lock :size="16" />
        예치중
      </span>
      <div class="held-right">
        <strong class="held-amount">{{ formatKRW(lockedBalance) }}</strong>
        <button
          type="button"
          class="held-info"
          aria-label="예치중 안내"
          :aria-expanded="heldInfoOpen"
          :aria-controls="heldInfoId"
          @click="toggleHeldInfo"
        >
          <Info :size="16" />
        </button>
      </div>

      <div v-if="heldInfoOpen" :id="heldInfoId" class="held-popover" role="note">
        {{ HELD_TOOLTIP }}
      </div>
    </div>

    <TransactionList
      :transactions="transactions"
      :page="transactionPage"
      :loading="loading || transactionsLoading"
      @open-filter="onOpenFilter"
      @load-more="onLoadMore"
    />

    <TransactionFilterSheet
      :open="filterOpen"
      :model-value="appliedFilter"
      :workplaces="activeWorkplaces"
      @close="filterOpen = false"
      @apply="onApplyFilter"
    />
  </div>
</template>

<style scoped>
/* 예치중 요약 — 지갑 카드 밖 별도 라인. position:relative 는 안내 팝오버의 anchor. */
.held-summary {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: var(--space-md);
  padding: var(--space-md) var(--space-lg);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}
.held-label {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
  font-size: var(--text-md);
  color: var(--color-text-sub);
}
.held-right {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
}
.held-amount {
  font-size: var(--text-lg);
  font-weight: var(--weight-bold);
  color: var(--color-owner);
}
.held-info {
  display: inline-flex;
  color: var(--color-text-sub);
}

/* 이 라인은 화면 위쪽이라 아래로 펼쳐도 다른 요소를 가릴 걱정이 적다(SecuredEarningCard 와
   달리 탭바에 잘릴 일이 없다). 오른쪽 정렬 — 아이콘이 우측에 있어 왼쪽으로 펼치면 화면
   폭을 넘길 수 있다. */
.held-popover {
  position: absolute;
  z-index: 1;
  top: calc(100% + var(--space-sm));
  right: var(--space-lg);
  left: var(--space-lg);
  padding: var(--space-md);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-card);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
  line-height: 1.5;
}
</style>
