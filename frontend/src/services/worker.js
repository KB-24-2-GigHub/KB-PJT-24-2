import * as api from '@/services/api/workerApi'
import { invokeOperation } from '@/services/operationAdapter'
import { collectAllPages } from '@/utils/page'

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
export function listWorkerWorkplaces(params = {}) {
  return invokeLive('listWorkerWorkplaces', [params])
}

/**
 * 공유 후보 사업장 전체.
 *
 * 공유 시트는 "고를 수 있는 것을 전부 보여주는" 선택 화면이라 Page 하나만 읽으면 남은
 * 후보가 표시도 오류도 없이 사라진다.
 */
export function listAllWorkerWorkplaces() {
  return collectAllPages(listWorkerWorkplaces)
}

/** POST /api/attendance/scans (#167) — SPEC-161-01. */
export function scan(payload) {
  return invokeLive('scan', [payload])
}
