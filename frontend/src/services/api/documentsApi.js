import http from '@/services/http'

/**
 * 문서 API Client.
 * 승인 계약: docs/specs/API_SPEC.md '문서'
 *
 * 목록·상세·공유 이력은 공통 `{data:{content,page}}` Envelope를 사용하고, http 인터셉터가
 * 바깥 `data` 를 이미 벗기므로 여기서는 안쪽 payload 만 돌려준다.
 */

/**
 * 승인 Query 는 workplaceId·docType·page·size 뿐이다. 서버는 그 밖의 Query 가 하나라도
 * 있으면 400 으로 거부하므로(DocumentController.requireApprovedListQuery), 화면이 넘긴
 * 임의 파라미터를 그대로 흘려보내지 않는다.
 */
const LIST_QUERY_KEYS = ['workplaceId', 'docType', 'page', 'size']

function approvedListQuery(params) {
  return LIST_QUERY_KEYS.reduce((query, key) => {
    const value = params[key]
    if (value !== undefined && value !== null && value !== '') query[key] = value
    return query
  }, {})
}

export async function listDocuments(params = {}) {
  const { data } = await http.get('/documents', { params: approvedListQuery(params) })
  return data
}

/**
 * 문서 상세. SHARED 보건증은 목록이 준 workCaseId 를 반드시 함께 보내야 한다 — 서버는
 * 관계를 자동 선택하지 않고, 없거나 틀리면 다른 관계로 fallback 하지 않고 404 다.
 */
export async function getDocument(documentId, { workCaseId } = {}) {
  const params = workCaseId === undefined || workCaseId === null ? {} : { workCaseId }
  const { data } = await http.get(`/documents/${documentId}`, { params })
  return data
}

export async function uploadDocument(formData) {
  const { data } = await http.post('/documents', formData)
  return data
}

export async function updateDocumentIssuedDate(documentId, { issuedDate }) {
  const { data } = await http.patch(`/documents/${documentId}`, { issuedDate })
  return data
}

export async function deleteDocument(documentId) {
  await http.delete(`/documents/${documentId}`)
}

export async function getDocumentShares(documentId, params = {}) {
  const { data } = await http.get(`/documents/${documentId}/shares`, { params })
  return data
}

export async function shareDocument(documentId, { workplaceId }) {
  const { data } = await http.post(`/documents/${documentId}/shares`, { workplaceId })
  return data
}

export async function revokeShare(documentId, workplaceId) {
  await http.delete(`/documents/${documentId}/shares/${workplaceId}`)
}

export function documentFileUrl(documentId, mode = 'view') {
  const base = import.meta.env.VITE_API_BASE_URL || '/api'
  return `${base}/documents/${documentId}/file?mode=${mode}`
}

export function fetchDocumentFile(documentId, mode = 'view') {
  return http.get(`/documents/${documentId}/file`, {
    params: { mode },
    responseType: 'blob'
  })
}
