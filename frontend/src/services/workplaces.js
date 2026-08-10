import * as api from '@/services/api/workplacesApi'
import { invokeOperation, OPERATION_SUPPORT } from '@/services/operationAdapter'

const loadMock = import.meta.env.DEV ? () => import('@/mocks/workplacesMockApi') : null

function invoke(method, args = [], { support = OPERATION_SUPPORT.LIVE, ownerIssue } = {}) {
  return invokeOperation({
    operation: `workplaces.${method}`,
    support,
    ownerIssue,
    api: api[method],
    loadMock,
    mockMethod: method,
    args
  })
}

export function listWorkplaces(options = {}) {
  return invoke('listWorkplaces', [options])
}

export function createWorkplace(payload) {
  return invoke('createWorkplace', [payload])
}

export function updateWorkplace(workplaceId, payload) {
  return invoke('updateWorkplace', [workplaceId, payload], {
    support: OPERATION_SUPPORT.UNAVAILABLE,
    ownerIssue: 'WORKPLACE-002 (담당 구현 이슈 없음)'
  })
}

export function deleteWorkplace(workplaceId) {
  return invoke('deleteWorkplace', [workplaceId], {
    support: OPERATION_SUPPORT.UNAVAILABLE,
    ownerIssue: 'WORKPLACE-003 (Deferred)'
  })
}

// QR operations deliberately have no mock adapter: they represent a signed server token.
export function getWorkplaceQr(workplaceId) {
  return api.getWorkplaceQr(workplaceId)
}

export function reissueWorkplaceQr(workplaceId) {
  return api.reissueWorkplaceQr(workplaceId)
}
