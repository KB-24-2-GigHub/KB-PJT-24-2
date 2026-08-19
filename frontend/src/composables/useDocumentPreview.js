import { onBeforeUnmount, ref } from 'vue'

import { fetchDocumentFile } from '@/services/documents'

/**
 * 인증이 필요한 문서를 Blob으로 받은 뒤 현재 화면에서만 유효한 미리보기 URL을 만든다.
 * API와 프론트엔드의 Origin이 달라도 백엔드 응답을 iframe에 직접 넣지 않도록 한다.
 *
 * 다운로드도 같은 경로를 쓴다. 파일 URL을 새 탭·<a href>로 여는 방식은 세션 쿠키에만
 * 기대는 별도 경로가 되고 `Cache-Control: private, no-store` 응답을 브라우저 탭에 그대로
 * 남기므로 사용하지 않는다(#183).
 */
export function useDocumentPreview() {
  const previewUrl = ref('')
  // PDF 는 Blob 자체가 필요하다. Object URL 을 <iframe> 에 넣는 방식은 모바일 브라우저에
  // 내장 PDF 뷰어가 없어 빈 화면으로 끝나므로, 원본을 canvas 에 직접 그린다(#433).
  const previewBlob = ref(null)
  const previewLoading = ref(false)
  const previewError = ref(null)
  const downloading = ref(false)

  let currentObjectUrl = ''
  let requestSequence = 0

  function revokeCurrentUrl() {
    if (currentObjectUrl) {
      URL.revokeObjectURL(currentObjectUrl)
      currentObjectUrl = ''
    }
    previewUrl.value = ''
    previewBlob.value = null
  }

  async function loadPreview(documentId) {
    const sequence = ++requestSequence
    revokeCurrentUrl()
    previewError.value = null

    if (!documentId) return ''

    previewLoading.value = true
    try {
      const blob = await fetchDocumentFile(documentId, 'view')
      if (sequence !== requestSequence) return ''

      currentObjectUrl = URL.createObjectURL(blob)
      previewUrl.value = currentObjectUrl
      previewBlob.value = blob
      return currentObjectUrl
    } catch (error) {
      if (sequence !== requestSequence) return ''
      previewError.value = error
      throw error
    } finally {
      if (sequence === requestSequence) previewLoading.value = false
    }
  }

  /**
   * mode=download Stream을 Blob으로 받아 저장한다. 임시 Object URL은 클릭 직후 해제해
   * 미리보기 URL과 수명을 공유하지 않는다.
   */
  async function downloadDocument(documentId, fileName) {
    if (!documentId) return
    downloading.value = true
    let objectUrl = ''
    try {
      const blob = await fetchDocumentFile(documentId, 'download')
      objectUrl = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = objectUrl
      link.download = fileName || ''
      document.body.appendChild(link)
      link.click()
      link.remove()
    } finally {
      if (objectUrl) URL.revokeObjectURL(objectUrl)
      downloading.value = false
    }
  }

  function clearPreview() {
    requestSequence += 1
    previewLoading.value = false
    previewError.value = null
    revokeCurrentUrl()
  }

  onBeforeUnmount(clearPreview)

  return {
    previewUrl,
    previewBlob,
    previewLoading,
    previewError,
    downloading,
    loadPreview,
    downloadDocument,
    clearPreview
  }
}
