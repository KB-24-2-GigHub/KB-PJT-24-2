<script setup>
/**
 * [B] 사장 홈(지갑)  ·  /owner/home  ·  OWNER  (탭 화면 — chrome 은 OwnerTabLayout)
 * 지갑 잔액·예치중(전 지점 합산, 지점 select 무관) + 충전·출금 + 송금상세 리스트.
 * 연계 API: GET /wallet · GET /wallet/transactions  →  @/services/wallet
 */
import { Info, Lock } from 'lucide-vue-next'
import { storeToRefs } from 'pinia'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import TransactionFilterSheet from '@/components/wallet/TransactionFilterSheet.vue'
import TransactionList from '@/components/wallet/TransactionList.vue'
import WalletBalanceCard from '@/components/wallet/WalletBalanceCard.vue'
import { useClickTogglePopover } from '@/composables/useClickTogglePopover'
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

const {
  rootEl: heldSummaryEl,
  open: heldInfoOpen,
  id: heldInfoId,
  toggle: toggleHeldInfo
} = useClickTogglePopover()
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
        <p class="held-popover-title">예치중 금액이란?</p>
        <p class="held-popover-body">
          근무 계약 시 지급 예정 임금을<br />
          미리 안전하게 보관(에스크로)하는 금액입니다.<br />
          정상 근무가 완료되면 알바생에게 지급되고,<br />
          노쇼 시 환불됩니다.
        </p>
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
   달리 탭바에 잘릴 일이 없다). 안내 버튼이 오른쪽에 있으니 팝오버도 오른쪽에 붙이고
   내용 길이만큼만 차지한다 — left까지 고정해 카드 폭을 꽉 채우면 문구가 짧을 때
   왼쪽에 빈 공간이 크게 남는다. max-width로 카드 왼쪽 끝(원래 left 위치)을 넘지
   않게 잡아둔다. */
.held-popover {
  position: absolute;
  z-index: 1;
  top: calc(100% + var(--space-sm));
  right: var(--space-lg);
  width: max-content;
  max-width: calc(100% - 2 * var(--space-lg));
  padding: var(--space-md);
  background: var(--color-owner-weak);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-card);
  line-height: 1.5;
}
.held-popover-title {
  font-size: var(--text-md);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}
.held-popover-body {
  margin-top: var(--space-xs);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
</style>
