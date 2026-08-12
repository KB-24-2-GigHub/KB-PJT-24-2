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

function invokeUnavailable(method, args, ownerIssue) {
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

// GET /api/worker/workplaces 백엔드 자체가 아직 없고 담당 이슈도 배정되지 않았다.
export function listWorkerWorkplaces() {
  return invokeUnavailable('listWorkerWorkplaces', [], 'WORKER-WORKPLACES (담당 구현 이슈 없음)')
}

// 백엔드(AttendanceScanController)·계약(SPEC-161-01)은 이미 있다. #167이 이 façade를
// LIVE로 전환하는 작업을 담당하므로, 그 전까지는 이 PR 범위 밖으로 두고 UNAVAILABLE로 남긴다.
export function scan(payload) {
  return invokeUnavailable('scan', [payload], '#167')
}

export function isWorkerScanAvailable() {
  return isMockOperationEnabled('worker.scan')
}
