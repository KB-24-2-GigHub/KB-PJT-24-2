/**
 * 공유 후보 사업장 조회 계약 테스트(#183).
 *
 * 공유 시트는 "고를 수 있는 것을 전부 보여주는" 선택 화면이다. Page 하나만 읽으면 남은
 * 후보가 표시도 오류도 없이 사라지고, 사용자는 공유할 수 있는 사업장을 아예 못 본다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/services/http', () => ({
  default: { get: vi.fn(), post: vi.fn() },
  idempotentPost: vi.fn()
}))

vi.mock('@/services/mockOperations', () => ({
  isMockOperationEnabled: vi.fn(() => false)
}))

import http from '@/services/http'
import { listAllWorkerWorkplaces, listWorkerWorkplaces } from '@/services/worker'

function pageOf(content, { number = 0, totalPages = 1 } = {}) {
  return { data: { content, page: { number, size: 100, totalElements: 0, totalPages } } }
}

function workplace(workplaceId) {
  return {
    workplaceId,
    workplaceName: `지점${workplaceId}`,
    ownerName: '김사장',
    startsAt: '2026-08-20T01:00:00Z',
    endsAt: '2026-08-20T09:00:00Z'
  }
}

describe('listWorkerWorkplaces', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('Page Query 를 그대로 전달한다', async () => {
    http.get.mockResolvedValue(pageOf([]))

    await listWorkerWorkplaces({ page: 1, size: 100 })

    expect(http.get).toHaveBeenCalledWith('/worker/workplaces', { params: { page: 1, size: 100 } })
  })
})

describe('listAllWorkerWorkplaces', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('한 Page 로 끝나면 한 번만 요청한다', async () => {
    http.get.mockResolvedValue(pageOf([workplace(1), workplace(2)]))

    await expect(listAllWorkerWorkplaces()).resolves.toEqual([workplace(1), workplace(2)])
    expect(http.get).toHaveBeenCalledTimes(1)
    // 승인 상한을 넘기면 서버가 400 으로 거부한다.
    expect(http.get).toHaveBeenCalledWith('/worker/workplaces', { params: { page: 0, size: 100 } })
  })

  it('남은 Page 를 모두 이어 붙인다', async () => {
    http.get
      .mockResolvedValueOnce(pageOf([workplace(1)], { number: 0, totalPages: 3 }))
      .mockResolvedValueOnce(pageOf([workplace(2)], { number: 1, totalPages: 3 }))
      .mockResolvedValueOnce(pageOf([workplace(3)], { number: 2, totalPages: 3 }))

    await expect(listAllWorkerWorkplaces()).resolves.toEqual([
      workplace(1),
      workplace(2),
      workplace(3)
    ])
    expect(http.get.mock.calls.map(([, config]) => config.params.page)).toEqual([0, 1, 2])
  })

  it('후보가 없으면 빈 목록을 돌려준다', async () => {
    http.get.mockResolvedValue(pageOf([], { totalPages: 0 }))

    await expect(listAllWorkerWorkplaces()).resolves.toEqual([])
    expect(http.get).toHaveBeenCalledTimes(1)
  })
})
