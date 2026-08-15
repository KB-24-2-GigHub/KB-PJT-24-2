/**
 * WORKER 문서함 역할 정책 계약 테스트(#113·#183).
 *
 * 이 화면의 위험은 "없어야 할 버튼이 남는 것"이다. 근로계약서는 시스템 생성 최종본이라
 * 사용자가 지우거나 바꿀 수 없고(409 CONTRACT_RETENTION_REQUIRED), 등록·수정·삭제·공유는
 * 전부 소유 보건증에만 열린다. 그래서 문서 유형별로 "존재하는 조작 버튼의 정확한 집합"을
 * 단언한다 — 금지 버튼을 하나씩 세는 방식으로는 새로 붙는 버튼을 잡지 못한다.
 */
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/services/documents', () => ({
  deleteDocument: vi.fn(),
  getDocumentShares: vi.fn(),
  listDocuments: vi.fn(),
  revokeShare: vi.fn(),
  shareDocument: vi.fn(),
  updateDocumentIssuedDate: vi.fn(),
  uploadDocument: vi.fn()
}))
vi.mock('@/services/worker', () => ({ listWorkerWorkplaces: vi.fn() }))

import {
  deleteDocument,
  getDocumentShares,
  listDocuments,
  revokeShare,
  shareDocument,
  uploadDocument
} from '@/services/documents'
import { listWorkerWorkplaces } from '@/services/worker'
import WorkerDocumentsView from '@/views/worker/WorkerDocumentsView.vue'

const PAGE = { number: 0, size: 20, totalElements: 1, totalPages: 1 }

const OWN_HEALTH_CERTIFICATE = {
  documentId: 5,
  docType: 'HEALTH_CERTIFICATE',
  status: 'ACTIVE',
  fileName: '보건증_20260601_김알바.jpg',
  mimeType: 'image/jpeg',
  issuedDate: '2026-06-01',
  expiresDate: '2027-06-01',
  source: 'OWN',
  sharedByName: null,
  workplaceId: null,
  workplaceName: null,
  workCaseId: null,
  capabilities: { canView: true, canDownload: true, canShare: true, canDelete: true },
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
  source: 'SHARED',
  sharedByName: '김사장',
  workplaceId: 1,
  workplaceName: '강남점',
  workCaseId: 201,
  capabilities: { canView: true, canDownload: true, canShare: false, canDelete: false },
  createdAt: '2026-07-22T09:20:00Z'
}

function pageOf(content) {
  return { content, page: { ...PAGE, totalElements: content.length } }
}

function mountView() {
  return mount(WorkerDocumentsView, { global: { stubs: { teleport: true } } })
}

function normalized(node) {
  return node.text().replace(/\s+/g, ' ').trim()
}

function actionLabels(wrapper) {
  return wrapper.findAll('.doc-side button').map((button) => button.attributes('aria-label'))
}

describe('WorkerDocumentsView 역할별 조작 권한', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    getDocumentShares.mockResolvedValue(pageOf([]))
    listWorkerWorkplaces.mockResolvedValue(pageOf([]))
  })

  it('근로계약서 카드에는 어떤 조작 버튼도 붙지 않는다', async () => {
    listDocuments.mockResolvedValue(pageOf([EMPLOYMENT_CONTRACT]))

    const wrapper = mountView()
    await flushPromises()

    expect(actionLabels(wrapper)).toEqual([])
  })

  it('소유 보건증 카드는 공유·발급일 수정·삭제만 노출한다', async () => {
    listDocuments.mockResolvedValue(pageOf([OWN_HEALTH_CERTIFICATE]))

    const wrapper = mountView()
    await flushPromises()

    expect(actionLabels(wrapper)).toEqual(['공유 관리', '발급일 수정', '보건증 삭제'])
  })

  it('서버가 canDelete=false 로 내려주면 삭제 버튼을 그리지 않는다', async () => {
    listDocuments.mockResolvedValue(
      pageOf([
        {
          ...OWN_HEALTH_CERTIFICATE,
          capabilities: { ...OWN_HEALTH_CERTIFICATE.capabilities, canDelete: false }
        }
      ])
    )

    const wrapper = mountView()
    await flushPromises()

    expect(actionLabels(wrapper)).toEqual(['공유 관리', '발급일 수정'])
  })

  it('계약서에는 공유 이력을 조회하지 않는다', async () => {
    listDocuments.mockResolvedValue(pageOf([EMPLOYMENT_CONTRACT, OWN_HEALTH_CERTIFICATE]))

    mountView()
    await flushPromises()

    // 소유 보건증이 아닌 문서의 공유 이력은 서버가 거부한다.
    expect(getDocumentShares.mock.calls.map(([documentId]) => documentId)).toEqual([5])
  })
})

describe('WorkerDocumentsView 목록 조회', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    getDocumentShares.mockResolvedValue(pageOf([]))
    listWorkerWorkplaces.mockResolvedValue(pageOf([]))
    listDocuments.mockResolvedValue(pageOf([]))
  })

  it('유형 탭은 서버 docType Query 로 거른다', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(listDocuments).toHaveBeenLastCalledWith({ docType: undefined })

    // 화면에서 거르면 Page 밖(기본 20건 이후)의 문서가 조용히 사라진다.
    await wrapper.findAll('.tab')[2].trigger('click')
    await flushPromises()

    expect(listDocuments).toHaveBeenLastCalledWith({ docType: 'HEALTH_CERTIFICATE' })
  })
})

describe('WorkerDocumentsView 보건증 등록', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    getDocumentShares.mockResolvedValue(pageOf([]))
    listWorkerWorkplaces.mockResolvedValue(pageOf([]))
    listDocuments.mockResolvedValue(pageOf([]))
    uploadDocument.mockResolvedValue({ documentId: 9 })
  })

  it('승인 Enum 과 발급일만 Multipart 로 보낸다', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('.upload-btn').trigger('click')

    const picked = new File(['image'], '보건증.png', { type: 'image/png' })
    const fileInput = wrapper.find('input[type="file"]')
    Object.defineProperty(fileInput.element, 'files', { value: [picked] })
    await fileInput.trigger('change')
    await wrapper.find('input[type="date"]').setValue('2026-06-01')

    await wrapper.find('.sheet-footer button').trigger('click')
    await flushPromises()

    expect(uploadDocument).toHaveBeenCalledTimes(1)
    const formData = uploadDocument.mock.calls[0][0]
    // 만료일은 서버가 계산한다. HEALTH_CERT 별칭과 클라이언트 만료일은 계약에 없다.
    expect([...formData.keys()].sort()).toEqual(['docType', 'file', 'issuedDate'])
    expect(formData.get('docType')).toBe('HEALTH_CERTIFICATE')
    expect(formData.get('issuedDate')).toBe('2026-06-01')
  })

  it('서버 fieldErrors 를 해당 입력 칸에 표시한다', async () => {
    uploadDocument.mockRejectedValue({
      response: { status: 400, data: { code: 'VALIDATION_ERROR' } },
      fieldErrors: [{ field: 'issuedDate', reason: '발급일은 오늘 이후일 수 없습니다.' }]
    })

    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('.upload-btn').trigger('click')

    const picked = new File(['image'], '보건증.png', { type: 'image/png' })
    const fileInput = wrapper.find('input[type="file"]')
    Object.defineProperty(fileInput.element, 'files', { value: [picked] })
    await fileInput.trigger('change')
    await wrapper.find('input[type="date"]').setValue('2027-01-01')

    await wrapper.find('.sheet-footer button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('발급일은 오늘 이후일 수 없습니다.')
  })
})

describe('WorkerDocumentsView 보건증 삭제', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    listDocuments.mockResolvedValue(pageOf([OWN_HEALTH_CERTIFICATE]))
    getDocumentShares.mockResolvedValue(pageOf([]))
    listWorkerWorkplaces.mockResolvedValue(pageOf([]))
    deleteDocument.mockResolvedValue(undefined)
  })

  it('삭제 성공 후 목록을 다시 읽어 서버가 철회한 공유까지 반영한다', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('[aria-label="보건증 삭제"]').trigger('click')
    listDocuments.mockClear()
    await wrapper.findAll('.base-modal-footer button').at(-1).trigger('click')
    await flushPromises()

    expect(deleteDocument).toHaveBeenCalledWith(5)
    // 삭제는 문서와 모든 ACTIVE 공유를 한 트랜잭션에서 끝낸다. 화면에서 흉내내지 않고 다시 읽는다.
    expect(listDocuments).toHaveBeenCalledTimes(1)
  })
})

describe('WorkerDocumentsView 공유 관리', () => {
  const SHARES = [
    {
      shareId: 11,
      workplaceId: 1,
      workplaceName: '강남점',
      workCaseId: 201,
      status: 'ACTIVE',
      sharedAt: '2026-07-20T10:00:00Z',
      revokedAt: null,
      effectiveUntil: '2026-08-20T09:00:00Z'
    },
    {
      shareId: 12,
      workplaceId: 2,
      workplaceName: '홍대점',
      workCaseId: 202,
      status: 'REVOKED',
      sharedAt: '2026-07-10T10:00:00Z',
      revokedAt: '2026-07-15T10:00:00Z',
      effectiveUntil: null
    },
    {
      shareId: 13,
      workplaceId: 3,
      workplaceName: '판교점',
      workCaseId: 203,
      status: 'EXPIRED',
      sharedAt: '2026-06-10T10:00:00Z',
      revokedAt: null,
      effectiveUntil: '2026-07-01T09:00:00Z'
    }
  ]

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    listDocuments.mockResolvedValue(pageOf([OWN_HEALTH_CERTIFICATE]))
    getDocumentShares.mockResolvedValue(pageOf(SHARES))
    listWorkerWorkplaces.mockResolvedValue(
      pageOf([
        {
          workplaceId: 1,
          workplaceName: '강남점',
          ownerName: '김사장',
          startsAt: '2026-08-20T01:00:00Z',
          endsAt: '2026-08-20T09:00:00Z'
        },
        {
          workplaceId: 4,
          workplaceName: '신촌점',
          ownerName: '박사장',
          startsAt: '2026-08-25T01:00:00Z',
          endsAt: '2026-08-25T09:00:00Z'
        }
      ])
    )
  })

  it('철회·만료된 공유를 공유중으로 표시하지 않는다', async () => {
    const wrapper = mountView()
    await flushPromises()

    // 상태는 서버 계산값이다. REVOKED·EXPIRED 는 이미 접근이 끊긴 관계다.
    expect(wrapper.find('.doc-share').text()).toBe('공유중 · 강남점')
  })

  it('공유중인 지점은 ACTIVE 만, 공유 후보는 이미 공유한 지점을 뺀 나머지만 보여준다', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('[aria-label="공유 관리"]').trigger('click')
    await flushPromises()

    const sections = wrapper.findAll('.share-sec')
    expect(sections[0].findAll('.wp').map(normalized)).toEqual(['강남점'])
    expect(sections[1].findAll('.wp').map(normalized)).toEqual(['신촌점 · 박사장'])
  })

  it('공유 요청은 workplaceId 만 보내고 성공 후 현황을 다시 읽는다', async () => {
    shareDocument.mockResolvedValue({ shareId: 14 })

    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[aria-label="공유 관리"]').trigger('click')
    await flushPromises()

    getDocumentShares.mockClear()
    await wrapper.findAll('.share-sec')[1].find('button').trigger('click')
    await flushPromises()

    expect(shareDocument).toHaveBeenCalledWith(5, { workplaceId: 4 })
    expect(getDocumentShares).toHaveBeenCalledWith(5)
  })

  it('철회는 사업장 단위로 요청하고 성공 후 현황을 다시 읽는다', async () => {
    revokeShare.mockResolvedValue(undefined)

    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[aria-label="공유 관리"]').trigger('click')
    await flushPromises()

    getDocumentShares.mockClear()
    await wrapper.findAll('.share-sec')[0].find('button').trigger('click')
    await flushPromises()

    expect(revokeShare).toHaveBeenCalledWith(5, 1)
    expect(getDocumentShares).toHaveBeenCalledWith(5)
  })

  it('canShare=false 인 만료 보건증에는 공유 버튼을 그리지 않는다', async () => {
    listDocuments.mockResolvedValue(
      pageOf([
        {
          ...OWN_HEALTH_CERTIFICATE,
          status: 'EXPIRED',
          capabilities: { ...OWN_HEALTH_CERTIFICATE.capabilities, canShare: false }
        }
      ])
    )

    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[aria-label="공유 관리"]').trigger('click')
    await flushPromises()

    const targets = wrapper.findAll('.share-sec')[1]
    expect(targets.findAll('button')).toHaveLength(0)
    expect(targets.text()).toContain('만료된 보건증은 새로 공유할 수 없어요.')
  })
})
