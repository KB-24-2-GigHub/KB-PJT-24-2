import * as api from '@/services/api/workerApi'
import { isMockOperationEnabled } from '@/services/mockOperations'
import { invokeOperation, OPERATION_SUPPORT } from '@/services/operationAdapter'

const loadMock = import.meta.env.DEV ? () => import('@/mocks/workerMockApi') : null

function invokeLive(method, args = []) {
  return invokeOperation({
    operation: `worker.${method}`,
    api: api[method],
    loadMock,
    mockMethod: method,
    args
  })
}

// #167이 담당하는 QR 스캔·사업장 목록은 백엔드·계약이 아직 없어 UNAVAILABLE로 남긴다.
function invokeUnavailable(method, args = [], ownerIssue = '#167') {
  return invokeOperation({
    operation: `worker.${method}`,
    support: OPERATION_SUPPORT.UNAVAILABLE,
    ownerIssue,
    api: api[method],
    loadMock,
    mockMethod: method,
    args
  })
}

export function getWorkerHome() {
  return invokeLive('getWorkerHome')
}

export function listWorkerWorkCases(params = {}) {
  return invokeLive('listWorkerWorkCases', [params])
}

export function listWorkerWorkplaces() {
  return invokeUnavailable('listWorkerWorkplaces')
}

export function scan(payload) {
  return invokeUnavailable('scan', [payload])
}

export function isWorkerScanAvailable() {
  return isMockOperationEnabled('worker.scan')
}
