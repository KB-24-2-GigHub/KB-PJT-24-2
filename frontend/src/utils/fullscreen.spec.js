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

    requestFullscreenSafely()

    expect(requestFullscreen).toHaveBeenCalledOnce()
    document.documentElement.requestFullscreen = original
  })

  it('requestFullscreen()이 reject해도 에러가 밖으로 새지 않는다', async () => {
    const requestFullscreen = vi.fn().mockRejectedValue(new Error('user gesture required'))

    expect(() => requestFullscreenSafely({ requestFullscreen })).not.toThrow()
    // catch 핸들러의 microtask 가 처리될 시간을 준다 — 처리 안 되면 unhandled rejection.
    await Promise.resolve()
    await Promise.resolve()
  })
})
