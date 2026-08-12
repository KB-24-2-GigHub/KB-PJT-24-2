import * as api from '@/services/api/workerApi'
import { isMockOperationEnabled } from '@/services/mockOperations'
import { invokeOperation, OPERATION_SUPPORT } from '@/services/operationAdapter'

const loadMock = import.meta.env.DEV ? () => import('@/mocks/workerMockApi') : null
const OWNER_ISSUE = '#163-#169'

function invoke(method, args = []) {
  return invokeOperation({
    operation: `worker.${method}`,
    support: OPERATION_SUPPORT.UNAVAILABLE,
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

export function scan(payload) {
  return invoke('scan', [payload])
}

export function isWorkerScanAvailable() {
  return isMockOperationEnabled('worker.scan')
}
