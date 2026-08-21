<script setup>
/**
 * [F] 알바생 홈(안심지갑)  ·  /worker/home  ·  WORKER  (탭 화면)
 * 안심지갑 잔액·출금(입금 없음) + 오늘의 알바 일정 카드(work_case 8종 상태 + 지각 파생 뱃지)
 * + 근무 경과 예상금액(진행바·i 팝오버) + 서버가 확정한 세금 참고값.
 * 적립액·진행률은 서버가 준 기준값으로부터 화면에서 계산하는 표시 전용 참고 추정치다
 * (명세 DASH-001 "earning은 표시 계산값", DASH-002 Deferred — 지갑 잔액·
 * 예치금·실제 지급액·정산 금액을 결정하지 않음). 지갑 잔액·정산 금액은 서버 값 그대로.
 * 연계 API: GET /worker/home  →  @/stores/workerHome (loadHome)
 *   안심지갑 잔액은 GET /wallet  →  @/stores/wallet (loadWallet, OWNER와 공용 원천).
 * 출금 버튼 → /worker/wallet/withdraw (사장 출금 화면과 동일한 별도 화면 흐름).
 */
import { storeToRefs } from 'pinia'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import EmptyState from '@/components/common/EmptyState.vue'
import SecuredEarningCard from '@/components/worker/SecuredEarningCard.vue'
import TaxReferenceCard from '@/components/worker/TaxReferenceCard.vue'
import TodayWorkCaseCard from '@/components/worker/TodayWorkCaseCard.vue'
import WorkerWalletCard from '@/components/worker/WorkerWalletCard.vue'
import { useWalletStore } from '@/stores/wallet'
import { useWorkerHomeStore } from '@/stores/workerHome'

const router = useRouter()
const homeStore = useWorkerHomeStore()
const walletStore = useWalletStore()
const { todayWorkCase, earning, loading, error } = storeToRefs(homeStore)
const { availableBalance } = storeToRefs(walletStore)
// wallet Store는 loadWallet() 단독 호출의 실패를 자체 error로 추적하지 않으므로,
// 이 화면에서 직접 잡아 homeStore.error와 함께 하나의 오류 화면으로 묶는다.
const walletError = ref(false)

// 근무 경과 예상금액은 오늘 근무가 있을 때만 노출한다. 오늘 근무가 없으면 서버가
// todayWorkCase 자체를 null로 주므로(WorkerHomeResponse) earning도 함께 비어 있다.
// NO_SHOW·CANCELED는 지급을 기대할 수 없는 상태라 참고값도 노출하지 않는다(DEC-OPEN-DASHBOARD-BREAK).
const NO_EARNING_REFERENCE_STATUSES = ['NO_SHOW', 'CANCELED']
const showEarning = computed(
  () =>
    !!earning.value &&
    !!todayWorkCase.value &&
    !NO_EARNING_REFERENCE_STATUSES.includes(todayWorkCase.value.status)
)
const hasError = computed(() => !!error.value || walletError.value)
const errorMessage = computed(() =>
  error.value?.code === 'FEATURE_UNAVAILABLE'
    ? '알바생 홈은 현재 준비 중인 기능입니다.'
    : '홈 정보를 불러오지 못했습니다.'
)

onMounted(() => {
  homeStore.loadHome()
  walletError.value = false
  walletStore.loadWallet().catch(() => {
    walletError.value = true
  })
})

const goWithdraw = () => router.push('/worker/wallet/withdraw')
const goWorkCaseDetail = (workCase) => router.push(`/worker/work/work-cases/${workCase.workCaseId}`)
</script>

<template>
  <div class="worker-home">
    <EmptyState v-if="hasError" :message="errorMessage" />

    <template v-else>
      <WorkerWalletCard :available-balance="availableBalance" @withdraw="goWithdraw" />

      <TodayWorkCaseCard :work-case="todayWorkCase" @select="goWorkCaseDetail" />

      <SecuredEarningCard v-if="showEarning" :earning="earning" :work-case="todayWorkCase" />

      <TaxReferenceCard
        v-if="todayWorkCase?.taxReference"
        :tax-reference="todayWorkCase.taxReference"
      />

      <p v-if="loading" class="loading">불러오는 중…</p>
    </template>
  </div>
</template>

<style scoped>
.loading {
  margin-top: var(--space-md);
  text-align: center;
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
</style>
