import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/services/wallet', () => ({
  fetchWallet: vi.fn(),
  fetchTransactions: vi.fn()
}))

import { fetchTransactions, fetchWallet } from '@/services/wallet'
import { useWalletStore } from '@/stores/wallet'

describe('wallet store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    fetchWallet.mockReset().mockResolvedValue({
      currency: 'KRW',
      availableBalance: 100_000,
      lockedBalance: 20_000
    })
    fetchTransactions.mockReset()
  })

  it('거래 content와 page Metadata를 함께 보존한다', async () => {
    fetchTransactions.mockResolvedValue({
      content: [{ transactionId: 1 }],
      page: { number: 0, size: 20, totalElements: 21, totalPages: 2 }
    })
    const store = useWalletStore()

    await store.loadTransactions({ workplaceId: 7 })

    expect(store.transactions).toEqual([{ transactionId: 1 }])
    expect(store.transactionPage).toEqual({
      number: 0,
      size: 20,
      totalElements: 21,
      totalPages: 2
    })
    expect(fetchTransactions).toHaveBeenCalledWith(
      expect.objectContaining({ workplaceId: 7, sort: 'LATEST', page: 0, size: 20 })
    )
  })

  it('다음 Page를 같은 Query로 조회해 기존 content 뒤에 붙인다', async () => {
    fetchTransactions
      .mockResolvedValueOnce({
        content: [{ transactionId: 1 }],
        page: { number: 0, size: 1, totalElements: 2, totalPages: 2 }
      })
      .mockResolvedValueOnce({
        content: [{ transactionId: 2 }],
        page: { number: 1, size: 1, totalElements: 2, totalPages: 2 }
      })
    const store = useWalletStore()

    await store.loadTransactions({ type: 'FUNDING', size: 1 })
    await store.loadNextTransactions()

    expect(store.transactions).toEqual([{ transactionId: 1 }, { transactionId: 2 }])
    expect(fetchTransactions).toHaveBeenLastCalledWith(
      expect.objectContaining({ type: 'FUNDING', page: 1, size: 1 })
    )
  })

  it('진행 중 조회를 기다린 뒤 정산 확인용 새 거래 요청을 보장한다', async () => {
    let resolveFirst
    fetchTransactions
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveFirst = resolve
          })
      )
      .mockResolvedValueOnce({
        content: [{ transactionId: 2, type: 'ESCROW_RELEASE' }],
        page: { number: 0, size: 20, totalElements: 1, totalPages: 1 }
      })
    const store = useWalletStore()

    const firstRequest = store.loadTransactions()
    const refreshRequest = store.refreshTransactions()

    expect(fetchTransactions).toHaveBeenCalledTimes(1)
    resolveFirst({
      content: [{ transactionId: 1 }],
      page: { number: 0, size: 20, totalElements: 1, totalPages: 1 }
    })
    await Promise.all([firstRequest, refreshRequest])

    expect(fetchTransactions).toHaveBeenCalledTimes(2)
    expect(store.transactions).toEqual([{ transactionId: 2, type: 'ESCROW_RELEASE' }])
  })
})
