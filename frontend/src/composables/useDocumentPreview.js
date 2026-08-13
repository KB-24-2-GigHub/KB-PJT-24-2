import { onBeforeUnmount, ref } from 'vue'

import { fetchDocumentFile } from '@/services/documents'

/**
 * 인증이 필요한 문서를 Blob으로 받은 뒤 현재 화면에서만 유효한 미리보기 URL을 만든다.
 * API와 프론트엔드의 Origin이 달라도 백엔드 응답을 iframe에 직접 넣지 않도록 한다.
 */
export function useDocumentPreview() {
  const previewUrl = ref('')
  const previewLoading = ref(false)
  const previewError = ref(null)

  let currentObjectUrl = ''
  let requestSequence = 0

  function revokeCurrentUrl() {
    if (currentObjectUrl) {
      URL.revokeObjectURL(currentObjectUrl)
      currentObjectUrl = ''
    }
    previewUrl.value = ''
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
      return currentObjectUrl
    } catch (error) {
      if (sequence !== requestSequence) return ''
      previewError.value = error
      throw error
    } finally {
      if (sequence === requestSequence) previewLoading.value = false
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
    previewLoading,
    previewError,
    loadPreview,
    clearPreview
  }
}
