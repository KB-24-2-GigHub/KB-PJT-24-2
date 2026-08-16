import { DOC_IMAGE_MIME_TYPES, DOC_PDF_MIME_TYPE, DOC_TYPE } from '@/utils/constants'

/**
 * 문서 Item 표시 헬퍼.
 *
 * 문서함·뷰어 네 화면이 같은 판정을 각자 구현하면 허용 MIME 이나 라벨을 바꿀 때 한 곳만
 * 고치고 지나가기 쉽다. 목록·상세가 fileExt 를 주지 않으므로 형태 판정은 mimeType 하나가
 * 유일한 근거다.
 */

export function docTypeLabel(doc) {
  return DOC_TYPE[doc?.docType]?.label ?? '문서'
}

export function isImageDocument(doc) {
  return DOC_IMAGE_MIME_TYPES.includes(doc?.mimeType)
}

export function isPdfDocument(doc) {
  return doc?.mimeType === DOC_PDF_MIME_TYPE
}

/**
 * 문서 조회·파일 접근 실패 문구.
 *
 * 서버는 없는 문서, 비당사자, 삭제·철회·만료를 모두 404 로 통일해 존재를 숨긴다(403·410
 * 으로 구분하지 않는다). 상태 코드 분기를 여기 한 곳에 두고, 404 에서 사용자가 실제로 할
 * 수 있는 안내만 화면이 넘긴다.
 */
export function documentAccessErrorMessage(error, notFoundMessage) {
  return error?.response?.status === 404 ? notFoundMessage : '문서를 불러오지 못했어요.'
}
