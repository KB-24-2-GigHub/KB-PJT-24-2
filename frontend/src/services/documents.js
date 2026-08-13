import * as api from '@/services/api/documentsApi'
import { invokeOperation, OPERATION_SUPPORT } from '@/services/operationAdapter'

const loadMock = import.meta.env.DEV ? () => import('@/mocks/documentsMockApi') : null
const OWNER_ISSUE = '#132/#183'

function invoke(method, args = []) {
  return invokeOperation({
    operation: `documents.${method}`,
    support: OPERATION_SUPPORT.UNAVAILABLE,
    ownerIssue: OWNER_ISSUE,
    api: api[method],
    loadMock,
    mockMethod: method,
    args
  })
}

export function isDocumentDeletable(document) {
  if (!document.workCaseId) return true
  return ['COMPLETED', 'NO_SHOW'].includes(document.workCaseStatus)
}

export function listDocuments(params = {}) {
  return invoke('listDocuments', [params])
}

export function uploadDocument(formData) {
  return invoke('uploadDocument', [formData])
}

export function updateDocumentIssuedDate(documentId, payload) {
  return invoke('updateDocumentIssuedDate', [documentId, payload])
}

export function deleteDocument(documentId) {
  return invoke('deleteDocument', [documentId])
}

// File URLs are only address construction. Server authorization remains authoritative.
export function documentFileUrl(documentId, mode = 'view') {
  return api.documentFileUrl(documentId, mode)
}

export function contractFileUrl(documentId, mode = 'view') {
  return api.documentFileUrl(documentId, mode)
}

// 계약 문서 Stream은 M4에서 제공되므로, M7 문서함 지원 여부와 별개로 직접 호출한다.
export function fetchDocumentFile(documentId, mode = 'view') {
  return api.fetchDocumentFile(documentId, mode)
}

export function getDocumentShares(documentId) {
  return invoke('getDocumentShares', [documentId])
}

export function shareDocument(documentId, payload) {
  return invoke('shareDocument', [documentId, payload])
}

export function revokeShare(documentId, workplaceId) {
  return invoke('revokeShare', [documentId, workplaceId])
}
