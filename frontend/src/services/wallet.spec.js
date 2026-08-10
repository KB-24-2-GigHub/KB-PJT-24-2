/**
 * 지갑 Service 계약 테스트.
 *
 * 기본 경로는 실제 HTTP이므로 URL·Params·Body를 고정한다. Mock 분기는 operation
 * selector를 테스트에서 직접 주입해 실행 환경 설정에 결과가 흔들리지 않게 한다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/services/http', () => ({
  default: { get: vi.fn() },
  idempotentPost: vi.fn()
}))

vi.mock('@/services/mockOperations', () => ({
  isMockOperationEnabled: vi.fn(() => false)
}))

import http, { idempotentPost } from '@/services/http'
import { isMockOperationEnabled } from '@/services/mockOperations'
import { chargeWallet, fetchTransactions, fetchWallet, withdrawWallet } from '@/services/wallet'

describe('wallet service', () => {
  beforeEach(() => {
    http.get.mockReset()
    idempotentPost.mockReset()
    isMockOperationEnabled.mockReturnValue(false)
  })

  it('지갑 잔액을 GET /wallet으로 조회하고 data를 그대로 반환한다', async () => {
    http.get.mockResolvedValue({
      data: { currency: 'KRW', availableBalance: 100_000, lockedBalance: 20_000 }
    })

    const result = await fetchWallet()

    expect(http.get).toHaveBeenCalledWith('/wallet')
    expect(result).toEqual({ currency: 'KRW', availableBalance: 100_000, lockedBalance: 20_000 })
  })

  it('거래 조회는 승인 Query Parameter를 그대로 GET /wallet/transactions에 전달한다', async () => {
    const page = { number: 0, size: 20, totalElements: 1, totalPages: 1 }
    http.get.mockResolvedValue({ data: { content: [{ transactionId: 1 }], page } })
    const params = {
      workplaceId: 7,
      from: '2026-07-01',
      to: '2026-07-31',
      type: 'FUNDING',
      minAmount: 1_000,
      maxAmount: 5_000,
      keyword: '허브',
      sort: 'AMOUNT_ASC',
      page: 1,
      size: 10
    }

    const result = await fetchTransactions(params)

    expect(http.get).toHaveBeenCalledWith('/wallet/transactions', { params })
    expect(result).toEqual({ content: [{ transactionId: 1 }], page })
  })

  it('충전은 멱등 POST로 bankCode/accountNo/pin/amount만 보낸다', async () => {
    idempotentPost.mockResolvedValue({
      data: { fundingOrderId: 10, status: 'COMPLETED', bankTransactionId: 20 }
    })

    const result = await chargeWallet({
      bankCode: '004',
      accountNo: '170000000001',
      pin: '0000',
      amount: 100_000
    })

    const [url, body] = idempotentPost.mock.calls[0]
    expect(url).toBe('/wallet/funding-orders')
    expect(body).toEqual({
      bankCode: '004',
      accountNo: '170000000001',
      pin: '0000',
      amount: 100_000
    })
    expect(result).toEqual({ fundingOrderId: 10, status: 'COMPLETED', bankTransactionId: 20 })
  })

  it('충전은 호출자가 보존한 Idempotency Key를 그대로 넘긴다', async () => {
    idempotentPost.mockResolvedValue({
      data: { fundingOrderId: 10, status: 'COMPLETED', bankTransactionId: 20 }
    })

    await chargeWallet(
      { bankCode: '004', accountNo: '170000000001', pin: '0000', amount: 100_000 },
      { idempotencyKey: 'keep-this-key' }
    )

    expect(idempotentPost.mock.calls[0][2]).toEqual({ idempotencyKey: 'keep-this-key' })
  })

  it('출금은 멱등 POST로 bankCode/accountNo/amount만 보내고 pin을 포함하지 않는다', async () => {
    idempotentPost.mockResolvedValue({
      data: { withdrawalRequestId: 11, status: 'COMPLETED', bankTransactionId: 21 }
    })

    const result = await withdrawWallet({
      bankCode: '088',
      accountNo: '170000000002',
      amount: 50_000
    })

    const [url, body] = idempotentPost.mock.calls[0]
    expect(url).toBe('/wallet/withdrawal-requests')
    expect(body).toEqual({ bankCode: '088', accountNo: '170000000002', amount: 50_000 })
    expect(result).toEqual({ withdrawalRequestId: 11, status: 'COMPLETED', bankTransactionId: 21 })
  })

  it('출금은 호출자가 보존한 Idempotency Key를 그대로 넘긴다', async () => {
    idempotentPost.mockResolvedValue({
      data: { withdrawalRequestId: 11, status: 'COMPLETED', bankTransactionId: 21 }
    })

    await withdrawWallet(
      { bankCode: '088', accountNo: '170000000002', amount: 50_000 },
      { idempotencyKey: 'keep-this-withdrawal-key' }
    )

    expect(idempotentPost.mock.calls[0][2]).toEqual({ idempotencyKey: 'keep-this-withdrawal-key' })
  })
})

describe('wallet Mock service', () => {
  beforeEach(() => {
    isMockOperationEnabled.mockReturnValue(true)
  })

  it('잘못된 Demo PIN은 계좌 존재 여부를 구분하지 않는 승인 오류로 거부한다', async () => {
    await expect(
      chargeWallet({
        bankCode: '004',
        accountNo: '170000000001',
        pin: '1234',
        amount: 100_000
      })
    ).rejects.toMatchObject({
      code: 'FORBIDDEN',
      response: {
        status: 403,
        data: { code: 'FORBIDDEN', message: '계좌를 사용할 수 없습니다.' }
      }
    })
  })

  it('올바른 Demo PIN은 잔액 대신 승인 식별자와 상태만 반환한다', async () => {
    const result = await chargeWallet({
      bankCode: '004',
      accountNo: '170000000001',
      pin: '0000',
      amount: 100_000
    })

    expect(Object.keys(result).sort()).toEqual(
      ['fundingOrderId', 'status', 'bankTransactionId'].sort()
    )
    expect(result).not.toHaveProperty('availableBalance')
  })
})
