/**
 * OWNER 문서함 읽기 전용 정책 계약 테스트(#113·#183).
 *
 * OWNER 문서함에서 서버가 허용하는 동작은 열람뿐이다. 계약서는 시스템이 생성하고
 * (POST /api/documents 는 보건증만 받는다), 공유 보건증은 알바생이 소유한다. 그래서
 * "이 화면에 존재하는 상호작용 요소의 정확한 집합"을 단언한다 — 업로드나 삭제가 다시
 * 붙으면 서버에 없는 요청을 만들거나 항상 거부되는 버튼을 노출하게 된다.
 *
 * 지점 Context 도 같은 무게로 다룬다. workplaceId 없는 목록 요청은 이 사장이 접근 가능한
 * 모든 지점의 문서를 돌려주므로, 사업장 Store 가 늦게 도착하면 다른 지점 문서가 섞인
 * 화면이 잠깐 그려진다.
 */
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('@/services/documents', () => ({ listDocuments: vi.fn() }))
vi.mock('@/services/workplaces', () => ({ listWorkplaces: vi.fn() }))

import { listDocuments } from '@/services/documents'
import { listWorkplaces } from '@/services/workplaces'
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

function pageOf(content, { totalPages = 1, number = 0 } = {}) {
  return { content, page: { number, size: 20, totalElements: content.length, totalPages } }
}

function mountView() {
  return mount(OwnerDocumentsView, { global: { stubs: { teleport: true } } })
}

describe('OwnerDocumentsView 지점 Context', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    listDocuments.mockResolvedValue(pageOf([]))
  })

  it('선택 지점이 정해지기 전에는 목록을 요청하지 않는다', async () => {
    // 사업장 Store 가 아직 응답 전인 상태.
    listWorkplaces.mockReturnValue(new Promise(() => {}))

    mountView()
    await flushPromises()

    // workplaceId 없이 나가면 서버가 전 지점 문서를 돌려준다.
    expect(listDocuments).not.toHaveBeenCalled()
  })

  it('사업장 Store 가 도착하면 그 지점으로 한 번만 조회한다', async () => {
    listWorkplaces.mockResolvedValue(pageOf([{ workplaceId: 7, name: '강남점', status: 'ACTIVE' }]))

    mountView()
    await flushPromises()

    expect(listDocuments).toHaveBeenCalledTimes(1)
    expect(listDocuments).toHaveBeenCalledWith({
      workplaceId: 7,
      docType: undefined,
      page: 0
    })
  })

  it('ACTIVE 사업장이 없으면 문서 대신 사업장 등록 안내를 보여준다', async () => {
    listWorkplaces.mockResolvedValue(pageOf([]))

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('등록된 사업장이 없습니다.')
    expect(listDocuments).not.toHaveBeenCalled()
  })
})

describe('OwnerDocumentsView 읽기 전용 정책', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    listWorkplaces.mockResolvedValue(pageOf([{ workplaceId: 1, name: '강남점', status: 'ACTIVE' }]))
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

  it('근로계약서의 3년 보관 후 자동 파기 정책을 안내한다', async () => {
    listDocuments.mockResolvedValue(pageOf([EMPLOYMENT_CONTRACT]))

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.notice').text()).toContain('근무 종료일로부터 3년간 보관된 뒤 자동 파기')
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
      docType: 'EMPLOYMENT_CONTRACT',
      page: 0
    })
  })

  it('만료 상태와 만료 예정일은 서버 값을 그대로 표시한다', async () => {
    listDocuments.mockResolvedValue(pageOf([{ ...SHARED_HEALTH_CERTIFICATE, status: 'EXPIRED' }]))

    const wrapper = mountView()
    await flushPromises()

    // 발급일+1년을 화면에서 추정하지 않는다.
    expect(wrapper.find('.badge--expired').exists()).toBe(true)
    expect(wrapper.find('.doc-meta').text()).toContain('만료일: 2027')
  })
})

describe('OwnerDocumentsView 목록 Page', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    listWorkplaces.mockResolvedValue(pageOf([{ workplaceId: 1, name: '강남점', status: 'ACTIVE' }]))
  })

  it('다음 Page 가 없으면 더 보기를 그리지 않는다', async () => {
    listDocuments.mockResolvedValue(pageOf([EMPLOYMENT_CONTRACT]))

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.more-btn').exists()).toBe(false)
  })

  it('다음 Page 를 이어 붙이고 마지막 Page 에서 더 보기를 감춘다', async () => {
    listDocuments
      .mockResolvedValueOnce(pageOf([EMPLOYMENT_CONTRACT], { totalPages: 2, number: 0 }))
      .mockResolvedValueOnce(pageOf([SHARED_HEALTH_CERTIFICATE], { totalPages: 2, number: 1 }))

    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.findAll('.doc-card')).toHaveLength(1)

    await wrapper.find('.more-btn').trigger('click')
    await flushPromises()

    // Page 를 하나만 읽으면 나머지 문서가 표시도 오류도 없이 사라진다.
    expect(listDocuments).toHaveBeenLastCalledWith({
      workplaceId: 1,
      docType: undefined,
      page: 1
    })
    expect(wrapper.findAll('.doc-card')).toHaveLength(2)
    expect(wrapper.find('.more-btn').exists()).toBe(false)
  })
})
