/**
 * 고정 QR 은 mock 플래그와 무관하게 항상 실 API 를 호출해야 한다.
 *
 * 일반 spec 은 API adapter 경로를 고정한다. 이 파일만 모든 operation의 Mock 선택을
 * 강제해 QR이 그 선택을 우회하고 실제 API adapter를 쓰는지 확인한다.
 *
 * 같은 서비스의 다른 mock 은 아직 살아 있어야 하므로 여기서 건드리지 않는다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/services/mockOperations', () => ({ isMockOperationEnabled: () => true }))

vi.mock('@/services/http', () => ({
  default: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() }
}))

import http from '@/services/http'
import { getWorkplaceQr, reissueWorkplaceQr } from '@/services/workplaces'

describe('고정 QR 은 operation mock 선택을 무시한다', () => {
  beforeEach(() => {
    http.get.mockReset()
    http.post.mockReset()
  })

  it('조회는 mock 이 켜져 있어도 서버를 호출한다', async () => {
    http.get.mockResolvedValue({ data: { workplaceId: 7, qrToken: 'real-token' } })

    const qr = await getWorkplaceQr(7)

    expect(http.get).toHaveBeenCalledWith('/workplaces/7/qr')
    expect(qr.qrToken).toBe('real-token')
  })

  it('재발급은 mock 이 켜져 있어도 서버를 호출한다', async () => {
    http.post.mockResolvedValue({ data: { workplaceId: 7, qrToken: 'real-new-token' } })

    const qr = await reissueWorkplaceQr(7)

    expect(http.post).toHaveBeenCalledWith('/workplaces/7/qr/reissue')
    expect(qr.qrToken).toBe('real-new-token')
  })

  it('같은 서비스의 명시 선택된 operation mock 은 동작한다', async () => {
    const { listWorkplaces } = await import('@/services/workplaces')

    const page = await listWorkplaces()

    // 사업장 목록은 아직 구현 전환 대상이 아니므로 mock 이 응답해야 한다.
    expect(http.get).not.toHaveBeenCalled()
    expect(page.content.length).toBeGreaterThan(0)
  })
})
