import * as api from '@/services/api/workCasesApi'
import { invokeOperation, OPERATION_SUPPORT } from '@/services/operationAdapter'

const loadMock = import.meta.env.DEV ? () => import('@/mocks/workCasesMockApi') : null

function invokeLive(method, args = []) {
  return invokeOperation({
    operation: `workCases.${method}`,
    api: api[method],
    loadMock,
    mockMethod: method,
    args
  })
}

function invokeUnavailable(method, args = [], ownerIssue = '#170-#177') {
  return invokeOperation({
    operation: `workCases.${method}`,
    support: OPERATION_SUPPORT.UNAVAILABLE,
    ownerIssue,
    api: api[method],
    loadMock,
    mockMethod: method,
    args
  })
}

export function getWorkCaseSummary(workplaceId) {
  return invokeLive('getWorkCaseSummary', [workplaceId])
}

export function listWorkCases(workplaceId, params = {}) {
  return invokeLive('listWorkCases', [workplaceId, params])
}

export function createWorkCase(workplaceId, payload) {
  return invokeLive('createWorkCase', [workplaceId, payload])
}

export function getWorkCase(workCaseId) {
  return invokeLive('getWorkCase', [workCaseId])
}

export function updateWorkCase(workCaseId, payload) {
  return invokeLive('updateWorkCase', [workCaseId, payload])
}

export function deleteWorkCase(workCaseId) {
  return invokeLive('deleteWorkCase', [workCaseId])
}

export function createInvite(workCaseId) {
  return invokeLive('createInvite', [workCaseId])
}

export function reissueInvite(workCaseId) {
  return invokeLive('reissueInvite', [workCaseId])
}

export function approveSettlement(workCaseId, options = {}) {
  return invokeLive('approveSettlement', [workCaseId, options])
}

export function approveNoShowRefund(workCaseId, options = {}) {
  return invokeLive('approveNoShowRefund', [workCaseId, options])
}

export function approveCheckOutMissingRefund(workCaseId, options = {}) {
  return invokeLive('approveCheckOutMissingRefund', [workCaseId, options])
}

export function getOwnerContact(workCaseId) {
  return invokeUnavailable('getOwnerContact', [workCaseId], '#163-#169')
}

export function listReports(workCaseId) {
  return invokeLive('listReports', [workCaseId])
}

export function createReport(workCaseId, payload) {
  return invokeLive('createReport', [workCaseId, payload])
}
