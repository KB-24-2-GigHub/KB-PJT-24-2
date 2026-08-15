import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  post: vi.fn(),
  requestUse: vi.fn(),
  responseUse: vi.fn()
}))

vi.mock('axios', () => ({
  default: {
    create: vi.fn(() => ({
      interceptors: {
        request: { use: mocks.requestUse },
        response: { use: mocks.responseUse }
      },
      post: mocks.post
    }))
  }
}))

import { authRequiredRedirect, errorMessage, idempotentPost } from '@/services/http'

describe('authRequiredRedirect', () => {
  it('초대 화면의 401은 WORKER 로그인과 원래 경로로 보낸다', () => {
    expect(authRequiredRedirect('/invitations/abc_DEF-123')).toBe(
      '/worker/login?redirect=%2Finvitations%2Fabc_DEF-123'
    )
  })

  it('일반 보호 화면의 401은 온보딩 복귀 경로를 유지한다', () => {
    expect(authRequiredRedirect('/owner/attendance', '?page=2')).toBe(
      '/?redirect=%2Fowner%2Fattendance%3Fpage%3D2'
    )
  })
})

describe('idempotentPost', () => {
  beforeEach(() => {
    mocks.post.mockReset()
  })

  it('자동 재시도와 이후 동일 의도 재시도에서 호출자가 보존한 키를 유지한다', async () => {
    const networkError = new Error('network')
    mocks.post.mockRejectedValueOnce(networkError).mockResolvedValueOnce({ data: { ok: true } })

    await idempotentPost(
      '/wallet/funding-orders',
      { bankCode: '004' },
      { retries: 1, idempotencyKey: 'funding-intent-203' }
    )

    expect(mocks.post).toHaveBeenCalledTimes(2)
    for (const [, , config] of mocks.post.mock.calls) {
      expect(config.headers['Idempotency-Key']).toBe('funding-intent-203')
    }

    mocks.post.mockResolvedValueOnce({ data: { ok: true } })
    await idempotentPost(
      '/wallet/funding-orders',
      { bankCode: '004' },
      { retries: 0, idempotencyKey: 'funding-intent-203' }
    )

    expect(mocks.post.mock.calls[2][2].headers['Idempotency-Key']).toBe('funding-intent-203')
  })

  it('Body를 생략한 요청은 JSON null 대신 undefined를 전송한다', async () => {
    mocks.post.mockResolvedValue({ data: { ok: true } })

    await idempotentPost('/invitations/token/accept', undefined, {
      idempotencyKey: 'accept-intent-1'
    })

    expect(mocks.post).toHaveBeenCalledWith(
      '/invitations/token/accept',
      undefined,
      expect.objectContaining({
        headers: expect.objectContaining({ 'Idempotency-Key': 'accept-intent-1' })
      })
    )
  })
})

describe('errorMessage', () => {
  it('서버가 사유별로 구분해 준 문구를 그대로 쓴다', () => {
    // 400 workplaceId(만료·후보 없음)와 409(중복 공유·복수 근무 건)를 한 문구로 뭉개면
    // 사용자가 무엇을 해야 하는지 알 수 없다.
    const expired = { response: { data: { message: '만료된 보건증은 공유할 수 없습니다.' } } }
    const duplicated = {
      response: { data: { message: '이미 이 사업장에 공유 중인 보건증입니다.' } }
    }

    expect(errorMessage(expired, '공유에 실패했어요.')).toBe('만료된 보건증은 공유할 수 없습니다.')
    expect(errorMessage(duplicated, '공유에 실패했어요.')).toBe(
      '이미 이 사업장에 공유 중인 보건증입니다.'
    )
  })

  it.each([
    ['본문이 없는 네트워크 오류', new Error('network')],
    ['message 가 없는 응답', { response: { data: { code: 'INTERNAL_ERROR' } } }],
    ['공백뿐인 message', { response: { data: { message: '   ' } } }],
    ['message 가 문자열이 아닌 응답', { response: { data: { message: { ko: 'x' } } } }]
  ])('%s 는 화면 기본 문구로 떨어진다', (_name, error) => {
    expect(errorMessage(error, '공유에 실패했어요.')).toBe('공유에 실패했어요.')
  })
})
