import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'

vi.mock('@/services/documents', () => ({ fetchDocumentFile: vi.fn() }))

import { useDocumentPreview } from '@/composables/useDocumentPreview'
import { fetchDocumentFile } from '@/services/documents'

function mountPreview() {
  let api
  const Host = defineComponent({
    setup() {
      api = useDocumentPreview()
      return () => h('div')
    }
  })

  const wrapper = mount(Host)
  return { wrapper, api }
}

describe('useDocumentPreview', () => {
  const createObjectURL = vi.fn()
  const revokeObjectURL = vi.fn()

  beforeEach(() => {
    fetchDocumentFile.mockReset()
    createObjectURL.mockReset().mockReturnValue('blob:https://gighub.store/document-preview')
    revokeObjectURL.mockReset()
    vi.stubGlobal('URL', { createObjectURL, revokeObjectURL })
  })

  it('인증된 Stream을 Blob으로 받아 미리보기 URL을 만든다', async () => {
    const blob = new Blob(['pdf'], { type: 'application/pdf' })
    fetchDocumentFile.mockResolvedValue(blob)
    const { api } = mountPreview()

    await expect(api.loadPreview(99)).resolves.toBe('blob:https://gighub.store/document-preview')

    expect(fetchDocumentFile).toHaveBeenCalledWith(99, 'view')
    expect(createObjectURL).toHaveBeenCalledWith(blob)
    expect(api.previewUrl.value).toBe('blob:https://gighub.store/document-preview')
  })

  it('다운로드도 인증된 Stream을 Blob으로 받고 임시 URL을 즉시 해제한다', async () => {
    // 파일 URL을 새 탭·<a href>로 여는 별도 경로를 남기지 않는다(#183).
    const blob = new Blob(['pdf'], { type: 'application/pdf' })
    fetchDocumentFile.mockResolvedValue(blob)
    createObjectURL.mockReturnValue('blob:https://gighub.store/document-download')
    const { api } = mountPreview()

    await api.downloadDocument(9, '보건증_20260601_김알바.jpg')

    expect(fetchDocumentFile).toHaveBeenCalledWith(9, 'download')
    expect(createObjectURL).toHaveBeenCalledWith(blob)
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:https://gighub.store/document-download')
    // 미리보기 URL과 수명을 공유하지 않는다.
    expect(api.previewUrl.value).toBe('')
  })

  it('다운로드가 실패해도 임시 URL과 진행 상태를 남기지 않는다', async () => {
    const error = Object.assign(new Error('not found'), { response: { status: 404 } })
    fetchDocumentFile.mockRejectedValue(error)
    const { api } = mountPreview()

    await expect(api.downloadDocument(9, '보건증.jpg')).rejects.toBe(error)

    expect(createObjectURL).not.toHaveBeenCalled()
    expect(api.downloading.value).toBe(false)
  })

  it('새 문서를 열거나 화면을 닫으면 기존 Object URL을 해제한다', async () => {
    fetchDocumentFile.mockResolvedValue(new Blob(['pdf']))
    createObjectURL
      .mockReturnValueOnce('blob:https://gighub.store/first')
      .mockReturnValueOnce('blob:https://gighub.store/second')
    const { wrapper, api } = mountPreview()

    await api.loadPreview(1)
    await api.loadPreview(2)
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:https://gighub.store/first')

    wrapper.unmount()
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:https://gighub.store/second')
  })
})
