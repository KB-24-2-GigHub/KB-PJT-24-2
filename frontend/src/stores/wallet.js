import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { fetchTransactions, fetchWallet } from '@/services/wallet'

const DEFAULT_QUERY = Object.freeze({ sort: 'LATEST', page: 0, size: 20 })
const emptyPage = () => ({ number: 0, size: 20, totalElements: 0, totalPages: 0 })

/** 지갑 요약과 승인 거래 Page를 함께 보존하는 공용 Store. */
export const useWalletStore = defineStore('wallet', () => {
  const currency = ref('KRW')
  const availableBalance = ref(0)
  const lockedBalance = ref(0)
  const transactions = ref([])
  const transactionPage = ref(emptyPage())
  const transactionQuery = ref({ ...DEFAULT_QUERY })
  const loading = ref(false)
  const transactionsLoading = ref(false)
  const error = ref(null)
  let transactionsRequest = null

  const hasNextTransactionPage = computed(
    () => transactionPage.value.number + 1 < transactionPage.value.totalPages
  )

  async function loadWallet() {
    const data = await fetchWallet()
    currency.value = data.currency
    availableBalance.value = data.availableBalance
    lockedBalance.value = data.lockedBalance
  }

  async function loadTransactions(params = {}, { append = false, force = false } = {}) {
    if (transactionsRequest) {
      try {
        await transactionsRequest
      } catch (requestError) {
        if (!force) throw requestError
      }
      // 정산 직후 강제 갱신은 기존 요청의 결과로 대신하지 않고 반드시 새 요청을 만든다.
      if (force) return loadTransactions(params, { append, force: true })
      return
    }

    const nextQuery = append
      ? { ...transactionQuery.value, ...params }
      : { ...DEFAULT_QUERY, ...params, page: params.page ?? 0 }

    transactionsLoading.value = true
    const request = fetchTransactions(nextQuery).then((data) => {
      // 다음 Page만 이어 붙이고, 필터나 정렬이 바뀐 첫 Page는 기존 목록을 교체한다.
      transactions.value = append ? [...transactions.value, ...data.content] : [...data.content]
      transactionPage.value = { ...data.page }
      transactionQuery.value = { ...nextQuery }
    })
    transactionsRequest = request
    try {
      await request
    } finally {
      if (transactionsRequest === request) {
        transactionsRequest = null
        transactionsLoading.value = false
      }
    }
  }

  /** 진행 중 조회가 있으면 기다린 뒤 정산 결과를 확인할 새 첫 Page 요청을 보장한다. */
  function refreshTransactions(params = {}) {
    return loadTransactions(params, { force: true })
  }

  async function loadNextTransactions() {
    if (!hasNextTransactionPage.value || transactionsLoading.value) return
    await loadTransactions(
      { ...transactionQuery.value, page: transactionPage.value.number + 1 },
      { append: true }
    )
  }

  /** 홈 진입 때 전체 지갑 요약과 거래 첫 Page를 함께 로드한다. */
  async function loadHome(params = {}) {
    loading.value = true
    error.value = null
    try {
      await Promise.all([loadWallet(), loadTransactions(params)])
    } catch (e) {
      error.value = e
    } finally {
      loading.value = false
    }
  }

  return {
    currency,
    availableBalance,
    lockedBalance,
    transactions,
    transactionPage,
    transactionQuery,
    hasNextTransactionPage,
    loading,
    transactionsLoading,
    error,
    loadWallet,
    loadTransactions,
    refreshTransactions,
    loadNextTransactions,
    loadHome
  }
})
