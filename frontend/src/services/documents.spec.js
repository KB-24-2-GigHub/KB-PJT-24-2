/**
 * 문서 도메인 API 계약 테스트(#183).
 *
 * 여기서 고정하는 것은 "화면이 서버에 무엇을 보내는가"다. #132·#180·#181 이 열어둔 경로는
 * 승인 Query·Body 만 받고, 그 밖의 값이 섞이면 400 이나 404 로 되돌아온다. 그래서 경로와
 * 파라미터를 정확한 집합으로 단언한다 — 금지 목록을 나열하는 방식으로는 새로 흘러 들어가는
 * 파라미터를 잡지 못한다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/services/http', () => ({
  default: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() }
}))

vi.mock('@/services/mockOperations', () => ({
  isMockOperationEnabled: vi.fn(() => false)
}))

import http from '@/services/http'
import {
  deleteDocument,
  fetchDocumentFile,
  getDocument,
  getDocumentShares,
  listAllDocumentShares,
  listDocuments,
  revokeShare,
  shareDocument,
  updateDocumentIssuedDate,
  uploadDocument
} from '@/services/documents'

const PAGE = { number: 0, size: 20, totalElements: 0, totalPages: 0 }

describe('문서 목록 Query', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    http.get.mockResolvedValue({ data: { content: [], page: PAGE } })
  })

  it('#183 부터 Mock 없이 실제 API 를 호출한다', async () => {
    await expect(listDocuments()).resolves.toEqual({ content: [], page: PAGE })
    expect(http.get).toHaveBeenCalledWith('/documents', { params: {} })
  })

  it('승인 Query 4개만 전달한다', async () => {
    await listDocuments({ workplaceId: 1, docType: 'HEALTH_CERTIFICATE', page: 2, size: 50 })

    expect(http.get).toHaveBeenCalledWith('/documents', {
      params: { workplaceId: 1, docType: 'HEALTH_CERTIFICATE', page: 2, size: 50 }
    })
  })

  it('승인 목록에 없는 Query 는 서버로 보내지 않는다', async () => {
    // 서버는 승인 Query 이외의 값이 하나라도 있으면 목록 전체를 400 으로 거부한다.
    await listDocuments({ workplaceId: 1, source: 'OWN', status: 'ACTIVE', keyword: '보건증' })

    expect(http.get).toHaveBeenCalledWith('/documents', { params: { workplaceId: 1 } })
  })

  it('비어 있는 필터 값은 Query 에서 빠진다', async () => {
    await listDocuments({ workplaceId: null, docType: undefined, page: 0 })

    expect(http.get).toHaveBeenCalledWith('/documents', { params: { page: 0 } })
  })
})

describe('문서 상세 조회', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    http.get.mockResolvedValue({ data: { documentId: 5, versions: [] } })
  })

  it('공유받은 보건증은 목록이 준 workCaseId 를 그대로 함께 보낸다', async () => {
    // 서버는 관계를 자동 선택하지 않는다. 이 값이 빠지면 권한이 있어도 404 다.
    await getDocument(5, { workCaseId: 201 })

    expect(http.get).toHaveBeenCalledWith('/documents/5', { params: { workCaseId: 201 } })
  })

  it('소유 문서와 계약서는 workCaseId 없이 조회한다', async () => {
    await getDocument(5)

    expect(http.get).toHaveBeenCalledWith('/documents/5', { params: {} })
  })
})

describe('보건증 생명주기', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('등록은 Multipart 를 그대로 POST 한다', async () => {
    const formData = new FormData()
    http.post.mockResolvedValue({ data: { documentId: 7 } })

    await expect(uploadDocument(formData)).resolves.toEqual({ documentId: 7 })
    expect(http.post).toHaveBeenCalledWith('/documents', formData)
  })

  it('수정은 issuedDate 만 PATCH 한다', async () => {
    http.patch.mockResolvedValue({ data: { documentId: 7 } })

    await updateDocumentIssuedDate(7, { issuedDate: '2026-06-01', expiresDate: '2027-06-01' })

    // 만료일은 서버가 계산한다. 화면이 보낸 값은 계약에 없다.
    expect(http.patch).toHaveBeenCalledWith('/documents/7', { issuedDate: '2026-06-01' })
  })

  it('삭제는 문서 경로만 호출한다', async () => {
    http.delete.mockResolvedValue(undefined)

    await deleteDocument(7)

    expect(http.delete).toHaveBeenCalledWith('/documents/7')
  })
})

describe('보건증 공유', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('공유 생성은 workplaceId 하나만 보낸다', async () => {
    http.post.mockResolvedValue({ data: { shareId: 11 } })

    await expect(
      shareDocument(5, { workplaceId: 1, workCaseId: 201, ownerId: 9 })
    ).resolves.toEqual({ shareId: 11 })

    // workCaseId 와 OWNER 는 서버가 근무 관계에서 파생한다. Client 입력을 신뢰하지 않는다.
    expect(http.post).toHaveBeenCalledWith('/documents/5/shares', { workplaceId: 1 })
  })

  it('공유 이력은 공통 Page Envelope 로 받는다', async () => {
    const content = [{ shareId: 11, workplaceId: 1, status: 'ACTIVE' }]
    http.get.mockResolvedValue({ data: { content, page: PAGE } })

    await expect(getDocumentShares(5)).resolves.toEqual({ content, page: PAGE })
    expect(http.get).toHaveBeenCalledWith('/documents/5/shares', { params: {} })
  })

  it('공유 이력은 Page 를 모두 모아 하나의 배열로 돌려준다', async () => {
    // 첫 Page 만 읽으면 오래된 ACTIVE 공유가 화면에서 사라진다. 이력은 최신 생성순이고
    // REVOKED·EXPIRED 까지 같은 목록에 섞여 있어 20건은 금방 넘는다.
    const activeOnFirstPage = { shareId: 30, workplaceId: 1, status: 'ACTIVE' }
    const revoked = { shareId: 20, workplaceId: 2, status: 'REVOKED' }
    const activeOnSecondPage = { shareId: 10, workplaceId: 3, status: 'ACTIVE' }
    http.get
      .mockResolvedValueOnce({
        data: {
          content: [activeOnFirstPage, revoked],
          page: { number: 0, size: 100, totalElements: 3, totalPages: 2 }
        }
      })
      .mockResolvedValueOnce({
        data: {
          content: [activeOnSecondPage],
          page: { number: 1, size: 100, totalElements: 3, totalPages: 2 }
        }
      })

    await expect(listAllDocumentShares(5)).resolves.toEqual([
      activeOnFirstPage,
      revoked,
      activeOnSecondPage
    ])
    expect(http.get).toHaveBeenCalledTimes(2)
    // 승인 상한을 넘기면 서버가 400 으로 거부한다.
    expect(http.get.mock.calls.map(([, config]) => config.params)).toEqual([
      { page: 0, size: 100 },
      { page: 1, size: 100 }
    ])
  })

  it('공유 이력이 한 Page 로 끝나면 한 번만 요청한다', async () => {
    http.get.mockResolvedValue({
      data: { content: [], page: { number: 0, size: 100, totalElements: 0, totalPages: 1 } }
    })

    await expect(listAllDocumentShares(5)).resolves.toEqual([])
    expect(http.get).toHaveBeenCalledTimes(1)
  })

  it('철회는 사업장 단위 경로를 호출한다', async () => {
    http.delete.mockResolvedValue(undefined)

    await revokeShare(5, 1)

    expect(http.delete).toHaveBeenCalledWith('/documents/5/shares/1')
  })
})

describe('파일 접근', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it.each([
    ['view', 'view'],
    ['download', 'download']
  ])('mode=%s Stream 을 Blob 으로 받는다', async (mode, expected) => {
    const blob = new Blob(['pdf'], { type: 'application/pdf' })
    http.get.mockResolvedValue(blob)

    await expect(fetchDocumentFile(9, mode)).resolves.toBe(blob)
    expect(http.get).toHaveBeenCalledWith('/documents/9/file', {
      params: { mode: expected },
      responseType: 'blob'
    })
  })
})
