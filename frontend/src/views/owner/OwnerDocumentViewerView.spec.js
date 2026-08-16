/**
 * OWNER 문서 뷰어 계약 테스트(#183).
 *
 * 목록이 넘긴 workCaseId 가 상세 요청까지 실제로 이어지는지가 이 화면의 핵심이다. 목록이
 * Query 를 만들어도 뷰어가 버리면 권한이 있는 OWNER 조차 404 를 받는다. 다운로드는 파일
 * URL 을 새 탭으로 여는 대신 인증된 Blob 경로를 써야 한다.
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
import OwnerDocumentViewerView from '@/views/owner/OwnerDocumentViewerView.vue'

const SHARED_HEALTH_CERTIFICATE = {
  documentId: 5,
  docType: 'HEALTH_CERTIFICATE',
  status: 'ACTIVE',
  fileName: '보건증_20260601_김알바.jpg',
  mimeType: 'image/jpeg',
  issuedDate: '2026-06-01',
  expiresDate: '2027-06-01',
  source: 'SHARED',
  sharedByName: '김알바',
  workplaceId: 1,
  workplaceName: '강남점',
  workCaseId: 201,
  capabilities: { canView: true, canDownload: true, canShare: false, canDelete: false },
  versions: [{ versionNo: 1, versionType: 'ORIGINAL', mimeType: 'image/jpeg', sizeBytes: 1024 }]
}

describe('OwnerDocumentViewerView', () => {
  const createObjectURL = vi.fn(() => 'blob:https://gighub.store/preview')
  const revokeObjectURL = vi.fn()

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    route.query = {}
    createObjectURL.mockReturnValue('blob:https://gighub.store/preview')
    vi.stubGlobal('URL', { createObjectURL, revokeObjectURL })
    getDocument.mockResolvedValue(SHARED_HEALTH_CERTIFICATE)
    fetchDocumentFile.mockResolvedValue(new Blob(['image'], { type: 'image/jpeg' }))
  })

  it('URL 로 받은 workCaseId 를 상세 요청에 그대로 전달한다', async () => {
    route.query = { workCaseId: '201' }

    mount(OwnerDocumentViewerView)
    await flushPromises()

    expect(getDocument).toHaveBeenCalledWith(5, { workCaseId: 201 })
  })

  it('workCaseId 가 없으면 임의로 만들어 붙이지 않는다', async () => {
    mount(OwnerDocumentViewerView)
    await flushPromises()

    expect(getDocument).toHaveBeenCalledWith(5, { workCaseId: undefined })
  })

  it('미리보기는 인증된 Blob Stream 으로 그린다', async () => {
    mount(OwnerDocumentViewerView)
    await flushPromises()

    expect(fetchDocumentFile).toHaveBeenCalledWith(5, 'view')
  })

  it('다운로드는 파일 URL 을 새 탭으로 열지 않고 Blob 으로 받는다', async () => {
    const wrapper = mount(OwnerDocumentViewerView)
    await flushPromises()

    await wrapper.find('[aria-label="다운로드"]').trigger('click')
    await flushPromises()

    expect(fetchDocumentFile).toHaveBeenCalledWith(5, 'download')
  })

  it('미리보기만 실패하면 문서 Metadata 와 미리보기 자리 안내를 그대로 보여준다', async () => {
    // 파일 Stream 일시 오류로 문서 전체를 접근 불가로 그리면, 권한도 있고 실재하는 문서를
    // "볼 수 없음"으로 잘못 알리게 된다.
    fetchDocumentFile.mockRejectedValue({ response: { status: 500 } })

    const wrapper = mount(OwnerDocumentViewerView)
    await flushPromises()

    expect(wrapper.text()).toContain('보건증_20260601_김알바.jpg')
    expect(wrapper.find('.viewer-placeholder').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('공유가 취소되었거나 근무 관계가 끝났을 수 있어요')
    expect(wrapper.find('[aria-label="다운로드"]').exists()).toBe(true)
  })

  it('canDownload 가 false 면 다운로드 버튼을 그리지 않는다', async () => {
    getDocument.mockResolvedValue({
      ...SHARED_HEALTH_CERTIFICATE,
      capabilities: { ...SHARED_HEALTH_CERTIFICATE.capabilities, canDownload: false }
    })

    const wrapper = mount(OwnerDocumentViewerView)
    await flushPromises()

    expect(wrapper.find('[aria-label="다운로드"]').exists()).toBe(false)
  })

  it('공유 철회 안내는 공유받은 보건증에만 붙인다', async () => {
    // 근로계약서는 알바생이 공유를 취소해도 사라지지 않는다.
    getDocument.mockResolvedValue({
      ...SHARED_HEALTH_CERTIFICATE,
      documentId: 1,
      docType: 'EMPLOYMENT_CONTRACT',
      source: 'OWN',
      sharedByName: null,
      expiresDate: null
    })

    const wrapper = mount(OwnerDocumentViewerView)
    await flushPromises()

    expect(wrapper.text()).not.toContain('알바생이 공유를 취소하거나')
    expect(wrapper.text()).toContain('시스템이 생성한 최종본')
  })

  it('접근이 사라진 문서(404)는 존재를 구분하지 않는 안내로 끝낸다', async () => {
    getDocument.mockRejectedValue({ response: { status: 404 } })

    const wrapper = mount(OwnerDocumentViewerView)
    await flushPromises()

    // 서버가 403·410 으로 구분하지 않으므로 화면도 원인을 단정하지 않는다.
    expect(wrapper.text()).toContain('공유가 취소되었거나 근무 관계가 끝났을 수 있어요')
    expect(wrapper.find('[aria-label="다운로드"]').exists()).toBe(false)
  })
})
