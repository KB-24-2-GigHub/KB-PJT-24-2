import * as api from '@/services/api/workerApi'
import { invokeOperation, OPERATION_SUPPORT } from '@/services/operationAdapter'

const loadMock = import.meta.env.DEV ? () => import('@/mocks/workerMockApi') : null
const OWNER_ISSUE = '#163-#169'

function invoke(method, args = [], support = OPERATION_SUPPORT.UNAVAILABLE) {
  return invokeOperation({
    operation: `worker.${method}`,
    support,
    ownerIssue: OWNER_ISSUE,
    api: api[method],
    loadMock,
    mockMethod: method,
    args
  })
}

export function getWorkerHome() {
  return invoke('getWorkerHome')
}

export function listWorkerWorkCases(params = {}) {
  return invoke('listWorkerWorkCases', [params])
}

export function listWorkerWorkplaces() {
  return invoke('listWorkerWorkplaces')
}

/** POST /api/attendance/scans (#167) — SPEC-161-01. */
export function scan(payload) {
  return invoke('scan', [payload], OPERATION_SUPPORT.LIVE)
}
