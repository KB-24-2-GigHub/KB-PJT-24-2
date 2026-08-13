export const SETTLEMENT_ACTION = Object.freeze({
  PAYOUT: 'PAYOUT',
  NO_SHOW_REFUND: 'NO_SHOW_REFUND'
})

/** 버튼은 안내용이다. 서버가 같은 상태·당사자·금액 조건을 최종 재검증한다. */
export function canApprovePayout(workCase) {
  return (
    workCase?.status === 'COMPLETED' &&
    workCase?.settlement?.status === 'SCHEDULED' &&
    workCase?.escrow?.status === 'HELD'
  )
}

export function canApproveNoShowRefund(workCase) {
  return (
    workCase?.status === 'NO_SHOW' &&
    workCase?.settlement?.status === 'WAITING' &&
    workCase?.settlement?.dueAt == null &&
    workCase?.escrow?.status === 'HELD'
  )
}

function isAmount(value) {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

/** 서버가 확정한 결과의 보존식만 검증하며 지급액이나 환불액을 새로 계산하지 않는다. */
export function isSettlementResultConsistent(action, result) {
  if (
    !isAmount(result?.originalEscrowAmount) ||
    !isAmount(result?.workerPaidAmount) ||
    !isAmount(result?.ownerRefundAmount) ||
    result.workerPaidAmount + result.ownerRefundAmount !== result.originalEscrowAmount
  ) {
    return false
  }

  if (action === SETTLEMENT_ACTION.PAYOUT) {
    return (
      result.status === 'COMPLETED' &&
      result.workerPaidAmount === result.originalEscrowAmount &&
      result.ownerRefundAmount === 0
    )
  }

  if (action === SETTLEMENT_ACTION.NO_SHOW_REFUND) {
    return (
      result.status === 'REFUNDED' &&
      result.workerPaidAmount === 0 &&
      result.ownerRefundAmount === result.originalEscrowAmount
    )
  }

  return false
}

export function hasSettlementTerminalState(action, workCase) {
  if (action === SETTLEMENT_ACTION.PAYOUT) {
    return workCase?.settlement?.status === 'COMPLETED' && workCase?.escrow?.status === 'RELEASED'
  }
  if (action === SETTLEMENT_ACTION.NO_SHOW_REFUND) {
    return workCase?.settlement?.status === 'REFUNDED' && workCase?.escrow?.status === 'REFUNDED'
  }
  return false
}

const ERROR_POLICIES = Object.freeze({
  AUTH_REQUIRED: {
    message: '로그인이 만료됐어요. 다시 로그인한 뒤 정산 상태를 확인해주세요.',
    refresh: false,
    preserveIntent: false
  },
  ROLE_MISMATCH: {
    message: '사장님만 지급 또는 환불을 승인할 수 있어요.',
    refresh: false,
    preserveIntent: false
  },
  RESOURCE_NOT_FOUND: {
    message: '근무를 찾을 수 없어요. 목록에서 다시 확인해주세요.',
    refresh: false,
    preserveIntent: false,
    returnToList: true
  },
  SETTLEMENT_ON_HOLD: {
    message: '열린 분쟁이 있어 지급 또는 환불이 보류됐어요.',
    refresh: true,
    preserveIntent: false
  },
  SETTLEMENT_NOT_READY: {
    message: '정산 상태가 바뀌었어요. 최신 상태를 다시 확인해주세요.',
    refresh: true,
    preserveIntent: false
  },
  SETTLEMENT_ALREADY_PROCESSED: {
    message: '이미 처리된 정산이에요. 최신 지갑과 거래내역을 확인해주세요.',
    refresh: true,
    preserveIntent: false
  },
  CONFLICT: {
    message: '같은 정산 요청을 처리하고 있어요. 상태 확인 후 같은 요청으로 다시 확인해주세요.',
    refresh: true,
    preserveIntent: true
  },
  IDEMPOTENCY_KEY_REUSED: {
    message: '요청 식별자가 다른 요청과 충돌했어요. 새로고침 후 상태를 확인하고 다시 시도해주세요.',
    refresh: true,
    preserveIntent: true
  },
  SETTLEMENT_TEMPORARILY_UNAVAILABLE: {
    message: '정산 처리가 잠시 지연되고 있어요. 상태 확인 후 같은 요청으로 다시 시도해주세요.',
    refresh: true,
    preserveIntent: true
  },
  INTERNAL_ERROR: {
    message: '정산 데이터 확인이 필요해요. 상태를 임의로 바꾸지 않았습니다.',
    refresh: true,
    preserveIntent: true
  }
})

const UNKNOWN_ERROR_POLICY = Object.freeze({
  message: '응답을 확인하지 못했어요. 상태 확인 후 같은 요청으로 다시 시도해주세요.',
  refresh: true,
  preserveIntent: true
})

export function settlementApprovalErrorPolicy(code) {
  return ERROR_POLICIES[code] ?? UNKNOWN_ERROR_POLICY
}
