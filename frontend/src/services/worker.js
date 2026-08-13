import * as api from '@/services/api/workerApi'
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

/** POST /api/attendance/scans (#167) — SPEC-161-01. */
export function scan(payload) {
  return invokeLive('scan', [payload])
}
