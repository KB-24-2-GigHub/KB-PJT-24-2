import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  canApproveCheckOutMissingRefund,
  canApproveNoShowRefund,
  canApprovePayout,
  clearSettlementIntent,
  getOrCreateSettlementIntent,
  hasSettlementTerminalState,
  isAmount,
  isSettlementResultConsistent,
  SETTLEMENT_ACTION,
  settlementApprovalErrorPolicy
} from '@/utils/settlement'

beforeEach(() => {
  sessionStorage.clear()
})

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

const CHECK_OUT_MISSING_REFUND_READY = {
  ...REFUND_READY,
  status: 'CHECK_OUT_MISSING'
}

const SNAPSHOT = {
  originalEscrowAmount: 90000,
  workerPaidAmount: 80000,
  ownerRefundAmount: 10000,
  deductionAmount: 10000,
  deductionBaseMinutes: 480,
  lateMinutes: 30,
  earlyLeaveMinutes: 15,
  calculationReason: 'CHECKED_OUT',
  calculationVersion: 'ATTENDANCE_V1',
  calculatedAt: '2026-08-13T01:00:00Z'
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

  it('CHECK_OUT_MISSING은 NO_SHOW와 구분한 별도 환불 동작만 노출한다', () => {
    expect(canApproveCheckOutMissingRefund(CHECK_OUT_MISSING_REFUND_READY)).toBe(true)
    expect(canApproveNoShowRefund(CHECK_OUT_MISSING_REFUND_READY)).toBe(false)
    expect(canApproveCheckOutMissingRefund(REFUND_READY)).toBe(false)
  })
})

describe('settlement result conservation', () => {
  it('금액은 0 이상의 안전한 정수만 허용한다', () => {
    expect(isAmount(0)).toBe(true)
    expect(isAmount(90000)).toBe(true)

    for (const value of [-1, 1.5, Number.MAX_SAFE_INTEGER + 1, '90000', null]) {
      expect(isAmount(value)).toBe(false)
    }
  })

  it('정상 정산은 부분 지급·부분 환불 보존식을 승인한다', () => {
    expect(
      isSettlementResultConsistent(SETTLEMENT_ACTION.PAYOUT, {
        ...SNAPSHOT,
        status: 'COMPLETED',
        settlementId: 1
      })
    ).toBe(true)

    expect(
      isSettlementResultConsistent(SETTLEMENT_ACTION.PAYOUT, {
        ...SNAPSHOT,
        status: 'COMPLETED',
        workerPaidAmount: 80000,
        ownerRefundAmount: 0
      })
    ).toBe(false)
  })

  it.each([
    [SETTLEMENT_ACTION.NO_SHOW_REFUND, 'NO_SHOW'],
    [SETTLEMENT_ACTION.CHECK_OUT_MISSING_REFUND, 'CHECK_OUT_MISSING']
  ])('%s는 WORKER 0원·OWNER 전액만 승인한다', (action, calculationReason) => {
    const refundSnapshot = {
      ...SNAPSHOT,
      workerPaidAmount: 0,
      ownerRefundAmount: 90000,
      deductionAmount: 90000,
      lateMinutes: 0,
      earlyLeaveMinutes: 0,
      calculationReason
    }
    expect(
      isSettlementResultConsistent(action, {
        ...refundSnapshot,
        status: 'REFUNDED',
        settlementId: 2
      })
    ).toBe(true)

    expect(
      isSettlementResultConsistent(action, {
        ...refundSnapshot,
        status: 'REFUNDED',
        workerPaidAmount: 1,
        ownerRefundAmount: 89999
      })
    ).toBe(false)
  })

  it('음수·소수·보존식·Snapshot 불일치 결과를 거절한다', () => {
    for (const result of [
      { ...SNAPSHOT, status: 'COMPLETED', originalEscrowAmount: -1 },
      { ...SNAPSHOT, status: 'COMPLETED', workerPaidAmount: 1.5 },
      { ...SNAPSHOT, status: 'COMPLETED', ownerRefundAmount: 0 },
      { ...SNAPSHOT, status: 'COMPLETED', deductionAmount: 9999 },
      { ...SNAPSHOT, status: 'COMPLETED', calculationVersion: null },
      { ...SNAPSHOT, status: 'COMPLETED', calculationVersion: 'ATTENDANCE_V2' },
      { ...SNAPSHOT, status: 'COMPLETED', calculationReason: 'NO_SHOW' }
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
      hasSettlementTerminalState(SETTLEMENT_ACTION.CHECK_OUT_MISSING_REFUND, {
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
    ['IDEMPOTENCY_KEY_REUSED', true, false],
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

describe('settlement intent persistence', () => {
  it('같은 Work Case와 승인 동작은 재진입해도 저장된 키를 재사용한다', () => {
    const createKey = vi.fn(() => 'persisted-settlement-key')

    expect(getOrCreateSettlementIntent(42, SETTLEMENT_ACTION.PAYOUT, createKey)).toBe(
      'persisted-settlement-key'
    )
    expect(getOrCreateSettlementIntent(42, SETTLEMENT_ACTION.PAYOUT, createKey)).toBe(
      'persisted-settlement-key'
    )
    expect(createKey).toHaveBeenCalledTimes(1)
  })

  it('Work Case 또는 승인 동작이 다르면 의도를 분리한다', () => {
    const createKey = vi
      .fn()
      .mockReturnValueOnce('payout-key')
      .mockReturnValueOnce('refund-key')
      .mockReturnValueOnce('other-work-key')

    expect(getOrCreateSettlementIntent(42, SETTLEMENT_ACTION.PAYOUT, createKey)).toBe('payout-key')
    expect(getOrCreateSettlementIntent(42, SETTLEMENT_ACTION.NO_SHOW_REFUND, createKey)).toBe(
      'refund-key'
    )
    expect(getOrCreateSettlementIntent(43, SETTLEMENT_ACTION.PAYOUT, createKey)).toBe(
      'other-work-key'
    )
  })

  it('서버 종료 상태 확인 뒤 저장된 의도를 제거한다', () => {
    const createKey = vi
      .fn()
      .mockReturnValueOnce('completed-intent-key')
      .mockReturnValueOnce('next-intent-key')

    expect(getOrCreateSettlementIntent(42, SETTLEMENT_ACTION.PAYOUT, createKey)).toBe(
      'completed-intent-key'
    )
    clearSettlementIntent(42, SETTLEMENT_ACTION.PAYOUT)
    expect(getOrCreateSettlementIntent(42, SETTLEMENT_ACTION.PAYOUT, createKey)).toBe(
      'next-intent-key'
    )
  })
})
