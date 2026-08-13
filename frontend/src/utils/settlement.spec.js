import { describe, expect, it } from 'vitest'

import {
  canApproveNoShowRefund,
  canApprovePayout,
  hasSettlementTerminalState,
  isSettlementResultConsistent,
  SETTLEMENT_ACTION,
  settlementApprovalErrorPolicy
} from '@/utils/settlement'

const PAYOUT_READY = {
  status: 'COMPLETED',
  escrow: { status: 'HELD', amount: 90000 },
  settlement: { status: 'SCHEDULED', amount: 90000, dueAt: '2026-08-13T01:00:00Z' }
}

const REFUND_READY = {
  status: 'NO_SHOW',
  escrow: { status: 'HELD', amount: 90000 },
  settlement: { status: 'WAITING', amount: 90000, dueAt: null }
}

describe('settlement approval state', () => {
  it('COMPLETED·SCHEDULED·HELD에서만 정상 지급을 노출한다', () => {
    expect(canApprovePayout(PAYOUT_READY)).toBe(true)

    for (const candidate of [
      { ...PAYOUT_READY, status: 'CHECK_OUT_MISSING' },
      { ...PAYOUT_READY, settlement: { ...PAYOUT_READY.settlement, status: 'ON_HOLD' } },
      { ...PAYOUT_READY, settlement: { ...PAYOUT_READY.settlement, status: 'PROCESSING' } },
      { ...PAYOUT_READY, settlement: { ...PAYOUT_READY.settlement, status: 'COMPLETED' } },
      { ...PAYOUT_READY, escrow: { ...PAYOUT_READY.escrow, status: 'RELEASED' } }
    ]) {
      expect(canApprovePayout(candidate)).toBe(false)
    }
  })

  it('NO_SHOW·WAITING·dueAt null·HELD에서만 전액 환불을 노출한다', () => {
    expect(canApproveNoShowRefund(REFUND_READY)).toBe(true)

    for (const candidate of [
      { ...REFUND_READY, status: 'READY' },
      {
        ...REFUND_READY,
        settlement: { ...REFUND_READY.settlement, dueAt: '2026-08-13T01:00:00Z' }
      },
      { ...REFUND_READY, settlement: { ...REFUND_READY.settlement, status: 'REFUNDED' } },
      { ...REFUND_READY, escrow: { ...REFUND_READY.escrow, status: 'REFUNDED' } }
    ]) {
      expect(canApproveNoShowRefund(candidate)).toBe(false)
    }
  })
})

describe('settlement result conservation', () => {
  it('정상 지급은 WORKER 전액·OWNER 0원만 승인한다', () => {
    expect(
      isSettlementResultConsistent(SETTLEMENT_ACTION.PAYOUT, {
        status: 'COMPLETED',
        originalEscrowAmount: 90000,
        workerPaidAmount: 90000,
        ownerRefundAmount: 0
      })
    ).toBe(true)

    expect(
      isSettlementResultConsistent(SETTLEMENT_ACTION.PAYOUT, {
        status: 'COMPLETED',
        originalEscrowAmount: 90000,
        workerPaidAmount: 80000,
        ownerRefundAmount: 10000
      })
    ).toBe(false)
  })

  it('NO_SHOW는 WORKER 0원·OWNER 전액만 승인한다', () => {
    expect(
      isSettlementResultConsistent(SETTLEMENT_ACTION.NO_SHOW_REFUND, {
        status: 'REFUNDED',
        originalEscrowAmount: 90000,
        workerPaidAmount: 0,
        ownerRefundAmount: 90000
      })
    ).toBe(true)

    expect(
      isSettlementResultConsistent(SETTLEMENT_ACTION.NO_SHOW_REFUND, {
        status: 'REFUNDED',
        originalEscrowAmount: 90000,
        workerPaidAmount: 1,
        ownerRefundAmount: 89999
      })
    ).toBe(false)
  })

  it('음수·소수·보존식 불일치 결과를 거절한다', () => {
    for (const result of [
      { status: 'COMPLETED', originalEscrowAmount: -1, workerPaidAmount: -1, ownerRefundAmount: 0 },
      {
        status: 'COMPLETED',
        originalEscrowAmount: 1.5,
        workerPaidAmount: 1.5,
        ownerRefundAmount: 0
      },
      {
        status: 'COMPLETED',
        originalEscrowAmount: 90000,
        workerPaidAmount: 89999,
        ownerRefundAmount: 0
      }
    ]) {
      expect(isSettlementResultConsistent(SETTLEMENT_ACTION.PAYOUT, result)).toBe(false)
    }
  })
})

describe('settlement source convergence', () => {
  it('지급과 환불의 Settlement·Escrow 종료 상태를 서로 구분한다', () => {
    expect(
      hasSettlementTerminalState(SETTLEMENT_ACTION.PAYOUT, {
        settlement: { status: 'COMPLETED' },
        escrow: { status: 'RELEASED' }
      })
    ).toBe(true)
    expect(
      hasSettlementTerminalState(SETTLEMENT_ACTION.NO_SHOW_REFUND, {
        settlement: { status: 'REFUNDED' },
        escrow: { status: 'REFUNDED' }
      })
    ).toBe(true)
    expect(
      hasSettlementTerminalState(SETTLEMENT_ACTION.PAYOUT, {
        settlement: { status: 'COMPLETED' },
        escrow: { status: 'HELD' }
      })
    ).toBe(false)
  })
})

describe('settlement approval error policy', () => {
  it.each([
    ['AUTH_REQUIRED', false, false],
    ['ROLE_MISMATCH', false, false],
    ['RESOURCE_NOT_FOUND', false, false],
    ['SETTLEMENT_ON_HOLD', true, false],
    ['SETTLEMENT_NOT_READY', true, false],
    ['SETTLEMENT_ALREADY_PROCESSED', true, false],
    ['CONFLICT', true, true],
    ['IDEMPOTENCY_KEY_REUSED', true, true],
    ['SETTLEMENT_TEMPORARILY_UNAVAILABLE', true, true],
    ['INTERNAL_ERROR', true, true]
  ])('%s를 승인된 재조회·의도 보존 정책으로 구분한다', (code, refresh, preserveIntent) => {
    expect(settlementApprovalErrorPolicy(code)).toMatchObject({ refresh, preserveIntent })
  })

  it('응답을 확정할 수 없는 오류는 같은 의도를 보존하고 재조회한다', () => {
    expect(settlementApprovalErrorPolicy(undefined)).toMatchObject({
      refresh: true,
      preserveIntent: true
    })
  })
})
