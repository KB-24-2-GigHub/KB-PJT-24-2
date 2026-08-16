/**
 * 뱃지 Mock 제거 회귀 테스트 (#184).
 *
 * #182 이전의 `getBadge` 는 USE_MOCK 이 켜지면 역할과 무관하게 `TRUST_OWNER` 고정 객체를
 * 돌려줬고, 그래서 WORKER 마이페이지가 OWNER 산정치를 성실근로 뱃지로 그렸다. 실 Endpoint 가
 * 살아난 지금은 Mock 을 켠 상태여도 실 요청만 나가야 한다.
 *
 * USE_MOCK 을 켜야 하므로 다른 Endpoint 의 Mock 분기까지 함께 켜진다 —
 * `users.spec.js` 와 파일을 나눈 이유다(`workplaces.qr-mock.spec.js` 와 같은 방식).
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/services/http', () => ({ default: { get: vi.fn() } }))
vi.mock('@/services/mockFlag', () => ({ USE_MOCK: true }))

import http from '@/services/http'
import { getBadge } from '@/services/users'

describe('getBadge — Mock opt-in 상태', () => {
  beforeEach(() => {
    http.get.mockReset()
  })

  it('USE_MOCK 이 켜져 있어도 서버에 묻고 응답을 그대로 돌려준다', async () => {
    const served = {
      badgeType: 'TRUST_WORKER',
      level: 2,
      recentCount: 21,
      remainingToNextLevel: 9,
      criterionLabel: '성실근로',
      criterionDesc: '누적 21건 중 정상 20건입니다.'
    }
    http.get.mockResolvedValue({ data: served })

    const badge = await getBadge()

    expect(http.get).toHaveBeenCalledWith('/users/me/badge')
    expect(badge).toEqual(served)
  })

  it('고정 TRUST_OWNER Fixture 를 대신 돌려주지 않는다', async () => {
    http.get.mockResolvedValue({ data: { badgeType: 'TRUST_WORKER', level: 0 } })

    const badge = await getBadge()

    expect(badge.badgeType).toBe('TRUST_WORKER')
  })
})
