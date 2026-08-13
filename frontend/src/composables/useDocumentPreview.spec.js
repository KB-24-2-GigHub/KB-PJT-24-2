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
