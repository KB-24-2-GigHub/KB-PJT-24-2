import * as api from '@/services/api/workerApi'
import { invokeOperation } from '@/services/operationAdapter'

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

export function getWorkerHome() {
  return invokeLive('getWorkerHome')
}

export function listWorkerWorkCases(params = {}) {
  return invokeLive('listWorkerWorkCases', [params])
}

/**
 * GET /api/worker/workplaces (#181) — 보건증 신규 공유 후보 사업장.
 * 특정 문서의 중복 공유·만료는 판정하지 않는다. 실제 공유 POST 가 다시 검증한다.
 */
export function listWorkerWorkplaces() {
  return invokeLive('listWorkerWorkplaces')
}

/** POST /api/attendance/scans (#167) — SPEC-161-01. */
export function scan(payload) {
  return invokeLive('scan', [payload])
}
