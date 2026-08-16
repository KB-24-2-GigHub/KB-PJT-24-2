import * as api from '@/services/api/documentsApi'
import { collectAllPages } from '@/utils/page'

/**
 * 문서 도메인 Facade.
 *
 * #132·#180·#181 로 문서 목록·상세·파일·보건증 생명주기·공유 Endpoint 가 모두 구현되어
 * Mock 어댑터 없이 실제 API 만 호출한다(#183). 권한·상태 판정은 전부 서버가 하고, 화면은
 * 응답의 `capabilities` 와 계산된 `status` 만 표시한다 — Client 판정을 보안 경계로 쓰지 않는다.
 */

export function listDocuments(params = {}) {
  return api.listDocuments(params)
}

export function getDocument(documentId, options = {}) {
  return api.getDocument(documentId, options)
}

export function uploadDocument(formData) {
  return api.uploadDocument(formData)
}

export function updateDocumentIssuedDate(documentId, payload) {
  return api.updateDocumentIssuedDate(documentId, payload)
}

export function deleteDocument(documentId) {
  return api.deleteDocument(documentId)
}

// File URLs are only address construction. Server authorization remains authoritative.
export function documentFileUrl(documentId, mode = 'view') {
  return api.documentFileUrl(documentId, mode)
}

export function contractFileUrl(documentId, mode = 'view') {
  return api.documentFileUrl(documentId, mode)
}

export function fetchDocumentFile(documentId, mode = 'view') {
  return api.fetchDocumentFile(documentId, mode)
}

export function getDocumentShares(documentId, params = {}) {
  return api.getDocumentShares(documentId, params)
}

/**
 * 공유 이력 전체.
 *
 * 이력은 REVOKED·EXPIRED 를 포함해 최신 생성순으로 내려온다. 첫 Page 만 읽으면 오래된
 * ACTIVE 공유가 뒤 Page 로 밀려 화면에서 사라지고, 그러면 세 가지가 한꺼번에 어긋난다 —
 * 카드의 '공유중' 목록이 실제보다 적어지고, 숨은 사업장이 '공유할 지점'에 다시 나와
 * 409 를 만들며, 그 공유를 철회할 경로 자체가 없어진다.
 */
export function listAllDocumentShares(documentId) {
  return collectAllPages((params) => api.getDocumentShares(documentId, params))
}

export function shareDocument(documentId, payload) {
  return api.shareDocument(documentId, payload)
}

export function revokeShare(documentId, workplaceId) {
  return api.revokeShare(documentId, workplaceId)
}
