/**
 * OWNER 문서함 읽기 전용 정책 계약 테스트(#113·#183).
 *
 * OWNER 문서함에서 서버가 허용하는 동작은 열람뿐이다. 계약서는 시스템이 생성하고
 * (POST /api/documents 는 보건증만 받는다), 공유 보건증은 알바생이 소유한다. 그래서
 * "이 화면에 존재하는 상호작용 요소의 정확한 집합"을 단언한다 — 업로드나 삭제가 다시
 * 붙으면 서버에 없는 요청을 만들거나 항상 거부되는 버튼을 노출하게 된다.
 */
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('@/services/documents', () => ({ listDocuments: vi.fn() }))

import { listDocuments } from '@/services/documents'
import { useWorkplaceStore } from '@/stores/workplace'
import OwnerDocumentsView from '@/views/owner/OwnerDocumentsView.vue'

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
  createdAt: '2026-06-01T01:00:00Z'
}

const EMPLOYMENT_CONTRACT = {
  documentId: 1,
  docType: 'EMPLOYMENT_CONTRACT',
  status: 'ACTIVE',
  fileName: '근로계약서_강남점_20260722_김알바.pdf',
  mimeType: 'application/pdf',
  issuedDate: '2026-07-22',
  expiresDate: null,
  source: 'OWN',
  sharedByName: null,
  workplaceId: 1,
  workplaceName: '강남점',
  workCaseId: 201,
  capabilities: { canView: true, canDownload: true, canShare: false, canDelete: false },
  createdAt: '2026-07-22T09:20:00Z'
}

function pageOf(content) {
  return { content, page: { number: 0, size: 20, totalElements: content.length, totalPages: 1 } }
}

function mountView() {
  const wrapper = mount(OwnerDocumentsView, { global: { stubs: { teleport: true } } })
  useWorkplaceStore().selectedId = 1
  return wrapper
}

describe('OwnerDocumentsView 읽기 전용 정책', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('탭과 문서 카드 외의 상호작용 요소가 없다', async () => {
    listDocuments.mockResolvedValue(pageOf([EMPLOYMENT_CONTRACT, SHARED_HEALTH_CERTIFICATE]))

    const wrapper = mountView()
    await flushPromises()

    // 업로드 input·업로드 버튼·삭제 버튼이 하나라도 남으면 이 집합이 달라진다.
    const controls = wrapper.findAll('button').map((button) => button.classes()[0])
    expect(controls).toEqual(['tab', 'tab', 'tab', 'doc-card', 'doc-card'])
    expect(wrapper.find('input').exists()).toBe(false)
  })

  it('공유받은 보건증 상세는 목록이 준 workCaseId 와 함께 연다', async () => {
    listDocuments.mockResolvedValue(pageOf([SHARED_HEALTH_CERTIFICATE]))

    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('.doc-card').trigger('click')

    // 서버는 관계를 자동 선택하지 않는다. Query 를 빠뜨리면 권한이 있어도 404 다.
    expect(push).toHaveBeenCalledWith({
      path: '/owner/documents/5',
      query: { workCaseId: '201' }
    })
  })

  it('근로계약서 상세는 workCaseId Query 없이 연다', async () => {
    listDocuments.mockResolvedValue(pageOf([EMPLOYMENT_CONTRACT]))

    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('.doc-card').trigger('click')

    // 계약서는 문서 자체가 work_case 를 갖는다. 관계 Query 는 계약에 없다.
    expect(push).toHaveBeenCalledWith({ path: '/owner/documents/1', query: {} })
  })

  it('선택한 지점과 유형 탭을 서버 Query 로 보낸다', async () => {
    listDocuments.mockResolvedValue(pageOf([]))

    const wrapper = mountView()
    await flushPromises()

    await wrapper.findAll('.tab')[1].trigger('click')
    await flushPromises()

    expect(listDocuments).toHaveBeenLastCalledWith({
      workplaceId: 1,
      docType: 'EMPLOYMENT_CONTRACT'
    })
  })

  it('만료 상태와 만료 예정일은 서버 값을 그대로 표시한다', async () => {
    listDocuments.mockResolvedValue(pageOf([{ ...SHARED_HEALTH_CERTIFICATE, status: 'EXPIRED' }]))

    const wrapper = mountView()
    await flushPromises()

    // 발급일+1년을 화면에서 추정하지 않는다.
    expect(wrapper.find('.badge--expired').exists()).toBe(true)
    expect(wrapper.find('.doc-expiry').text()).toContain('2027')
  })
})
