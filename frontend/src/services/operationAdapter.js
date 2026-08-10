import { isMockOperationEnabled } from '@/services/mockOperations'

export const OPERATION_SUPPORT = Object.freeze({
  LIVE: 'LIVE',
  UNAVAILABLE: 'UNAVAILABLE'
})

export class OperationUnavailableError extends Error {
  constructor(operation, ownerIssue) {
    super(`현재 사용할 수 없는 기능입니다. (${ownerIssue})`)
    this.name = 'OperationUnavailableError'
    this.code = 'FEATURE_UNAVAILABLE'
    this.operation = operation
    this.ownerIssue = ownerIssue
    this.response = {
      status: 503,
      data: {
        code: this.code,
        message: this.message,
        operation,
        ownerIssue
      }
    }
  }
}

/**
 * Select one adapter for one operation. Production never selects a mock because
 * isMockOperationEnabled is compile-time disabled outside DEV/Test.
 */
export async function invokeOperation({
  operation,
  support = OPERATION_SUPPORT.LIVE,
  api,
  loadMock,
  mockMethod,
  args = [],
  ownerIssue = '#293'
}) {
  if (isMockOperationEnabled(operation)) {
    if (!loadMock) {
      throw new Error(`Mock adapter is not configured for ${operation}`)
    }
    const adapter = await loadMock()
    const method = adapter[mockMethod]
    if (typeof method !== 'function') {
      throw new Error(`Mock adapter does not implement ${operation}`)
    }
    return method(...args)
  }

  if (support === OPERATION_SUPPORT.UNAVAILABLE) {
    throw new OperationUnavailableError(operation, ownerIssue)
  }

  return api(...args)
}
