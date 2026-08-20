import { describe, expect, it, vi } from 'vitest'

import { requestFullscreenSafely } from '@/utils/fullscreen'

describe('requestFullscreenSafely', () => {
  it('requestFullscreen이 있으면 호출한다', () => {
    const requestFullscreen = vi.fn().mockResolvedValue(undefined)

    requestFullscreenSafely({ requestFullscreen })

    expect(requestFullscreen).toHaveBeenCalledOnce()
  })

  it('requestFullscreen이 없는 환경(iOS Safari 등)에서는 아무 일도 하지 않는다', () => {
    expect(() => requestFullscreenSafely({})).not.toThrow()
  })

  it('요소를 주지 않으면 document.documentElement를 기본값으로 쓴다', () => {
    const original = document.documentElement.requestFullscreen
    const requestFullscreen = vi.fn().mockResolvedValue(undefined)
    document.documentElement.requestFullscreen = requestFullscreen

    try {
      requestFullscreenSafely()

      expect(requestFullscreen).toHaveBeenCalledOnce()
    } finally {
      // 단언이 실패해도 원래 값을 복원해 뒤따르는 테스트로 상태가 새지 않게 한다.
      document.documentElement.requestFullscreen = original
    }
  })

  it('반환된 Promise가 reject해도 직접 catch를 붙여 삼킨다', () => {
    // .not.toThrow() 만으로는 catch 가 실제로 붙었는지 알 수 없다(동기 호출은 원래
    // 던지지 않는다). 반환값에 .catch() 가 호출됐는지를 직접 확인해야 의미가 있다.
    const promiseCatch = vi.fn()
    const requestFullscreen = vi.fn(() => ({ catch: promiseCatch }))

    requestFullscreenSafely({ requestFullscreen })

    expect(promiseCatch).toHaveBeenCalledOnce()
  })

  it('requestFullscreen() 호출 자체가 동기적으로 throw해도 에러가 밖으로 새지 않는다', () => {
    // 명세상 Promise 를 reject 해야 하지만, 권한 없는 iframe 등 일부 구현은 동기
    // TypeError 를 던진다. .catch() 는 이 경우를 잡지 못하므로 try/catch 가 별도로 필요하다.
    const requestFullscreen = vi.fn(() => {
      throw new TypeError('Permissions check failed')
    })

    expect(() => requestFullscreenSafely({ requestFullscreen })).not.toThrow()
  })
})
