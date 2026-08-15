/**
 * WORKER 문서 뷰어 계약 테스트(#113·#183).
 *
 * 만료 표시는 서버가 계산한 expiresDate·status 만 쓴다. 발급일+1년을 화면에서 더하면
 * 윤년과 서울 날짜 경계에서 서버 판정과 어긋나고, 그 차이가 그대로 "공유 가능해 보이는데
 * 거부되는" 화면이 된다. 다운로드는 인증된 Blob 경로여야 한다.
 */
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const route = { params: { documentId: '5' }, query: {} }
// AppBackHeader 가 useRouter 를 쓴다.
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ back: vi.fn() }) }))
vi.mock('@/services/documents', () => ({
  getDocument: vi.fn(),
  fetchDocumentFile: vi.fn()
}))

import { fetchDocumentFile, getDocument } from '@/services/documents'
import WorkerDocumentViewerView from '@/views/worker/WorkerDocumentViewerView.vue'

const OWN_HEALTH_CERTIFICATE = {
  documentId: 5,
  docType: 'HEALTH_CERTIFICATE',
  status: 'ACTIVE',
  fileName: '보건증_20260601_김알바.jpg',
  mimeType: 'image/jpeg',
  issuedDate: '2026-06-01',
  expiresDate: '2027-06-01',
  source: 'OWN',
  capabilities: { canView: true, canDownload: true, canShare: true, canDelete: true },
  versions: [{ versionNo: 1, versionType: 'ORIGINAL', mimeType: 'image/jpeg', sizeBytes: 1024 }]
}

describe('WorkerDocumentViewerView', () => {
  const createObjectURL = vi.fn(() => 'blob:https://gighub.store/preview')
  const revokeObjectURL = vi.fn()

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    createObjectURL.mockReturnValue('blob:https://gighub.store/preview')
    vi.stubGlobal('URL', { createObjectURL, revokeObjectURL })
    getDocument.mockResolvedValue(OWN_HEALTH_CERTIFICATE)
    fetchDocumentFile.mockResolvedValue(new Blob(['image'], { type: 'image/jpeg' }))
  })

  it('상세 API 한 건만 호출하고 목록에서 문서를 찾지 않는다', async () => {
    mount(WorkerDocumentViewerView)
    await flushPromises()

    expect(getDocument).toHaveBeenCalledWith(5)
  })

  it('만료 예정일은 서버가 준 expiresDate 를 그대로 보여준다', async () => {
    getDocument.mockResolvedValue({
      ...OWN_HEALTH_CERTIFICATE,
      issuedDate: '2026-02-29',
      expiresDate: '2027-02-28'
    })

    const wrapper = mount(WorkerDocumentViewerView)
    await flushPromises()

    // 발급일+1년을 화면에서 더하면 윤년에서 서버와 어긋난다.
    expect(wrapper.text()).toContain('2027.02.28')
    // 2026-02-29 + 1년을 화면에서 계산하면 2027.03.01 로 하루 밀린다.
    expect(wrapper.text()).not.toContain('2027.03.01')
  })

  it('만료 상태는 서버 status 로만 표시한다', async () => {
    getDocument.mockResolvedValue({ ...OWN_HEALTH_CERTIFICATE, status: 'EXPIRED' })

    const wrapper = mount(WorkerDocumentViewerView)
    await flushPromises()

    expect(wrapper.find('.expired').exists()).toBe(true)
  })

  it('만료되지 않은 문서에는 만료 표시를 붙이지 않는다', async () => {
    const wrapper = mount(WorkerDocumentViewerView)
    await flushPromises()

    expect(wrapper.find('.expired').exists()).toBe(false)
  })

  it('다운로드는 인증된 Blob Stream 으로 받는다', async () => {
    const wrapper = mount(WorkerDocumentViewerView)
    await flushPromises()

    await wrapper.find('.download-btn').trigger('click')
    await flushPromises()

    expect(fetchDocumentFile).toHaveBeenCalledWith(5, 'download')
  })
})
