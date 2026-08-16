/**
 * 회원 서비스 계약 테스트 — 승인 프로필 계약을 고정한다.
 * PATCH /api/users/me 는 phone 외 필드를 무시하지 않고 400 으로 거부하므로,
 * 서비스가 승인 Body 만 만들어 보내는지 확인한다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/services/http', () => ({
  default: { get: vi.fn(), patch: vi.fn() }
}))

import http from '@/services/http'
import { getBadge, getMe, updateMe } from '@/services/users'

const serverProfile = {
  loginId: 'owner01',
  email: 'owner@test.com',
  name: '김사장',
  phone: '01012345678',
  role: 'OWNER',
  status: 'ACTIVE'
}

describe('getMe', () => {
  beforeEach(() => {
    http.get.mockReset()
    http.patch.mockReset()
  })

  it('사용자 ID 없이 내 프로필만 조회한다', async () => {
    http.get.mockResolvedValue({ data: serverProfile })

    const me = await getMe()

    expect(http.get).toHaveBeenCalledWith('/users/me')
    expect(me).toEqual(serverProfile)
  })
})

describe('getBadge', () => {
  beforeEach(() => {
    http.get.mockReset()
  })

  it('단수 Endpoint 를 호출하고 승인 Payload 를 그대로 돌려준다', async () => {
    http.get.mockResolvedValue({ data: { badgeType: 'TRUST_WORKER', level: 0 } })

    const badge = await getBadge()

    expect(http.get).toHaveBeenCalledWith('/users/me/badge')
    expect(badge).toEqual({ badgeType: 'TRUST_WORKER', level: 0 })
  })

  it('사용자 ID 를 받는 복수 Endpoint 를 호출하지 않는다', async () => {
    http.get.mockResolvedValue({ data: { badgeType: 'TRUST_OWNER', level: 1 } })

    await getBadge()

    expect(http.get).toHaveBeenCalledTimes(1)
    expect(http.get.mock.calls[0][0]).not.toContain('badges')
  })
})

describe('updateMe', () => {
  beforeEach(() => {
    http.get.mockReset()
    http.patch.mockReset()
    http.patch.mockResolvedValue({ data: serverProfile })
  })

  it('Body 에 phone 만 담아 보낸다', async () => {
    await updateMe({ phone: '010-9999-8888' })

    const [url, body] = http.patch.mock.calls[0]
    expect(url).toBe('/users/me')
    expect(Object.keys(body)).toEqual(['phone'])
  })

  it('전화번호를 구분 문자 없는 숫자로 정규화해 보낸다', async () => {
    await updateMe({ phone: '010-9999-8888' })

    expect(http.patch.mock.calls[0][1].phone).toBe('01099998888')
  })

  it('금지 필드를 함께 받아도 요청에 싣지 않는다', async () => {
    await updateMe({
      phone: '01012345678',
      name: '바꾸려는 이름',
      email: 'new@test.com',
      role: 'WORKER',
      profileImageUrl: 'https://example.com/a.png'
    })

    expect(Object.keys(http.patch.mock.calls[0][1])).toEqual(['phone'])
  })
})
