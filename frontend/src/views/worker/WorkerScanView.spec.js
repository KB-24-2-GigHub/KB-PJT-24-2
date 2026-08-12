import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/services/worker', () => ({
  isWorkerScanAvailable: vi.fn(() => false),
  scan: vi.fn()
}))

import WorkerScanView from '@/views/worker/WorkerScanView.vue'

describe('WorkerScanView unavailable boundary', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('Production 미구현 상태를 먼저 알리고 위치·카메라 권한을 요청하지 않는다', async () => {
    const getCurrentPosition = vi.fn()
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: { getCurrentPosition }
    })
    const getUserMedia = vi.fn()
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia }
    })

    const wrapper = mount(WorkerScanView)

    expect(wrapper.text()).toContain('QR 출퇴근은 현재 준비 중인 기능입니다.')
    expect(wrapper.get('.btn').attributes()).toHaveProperty('disabled')
    expect(wrapper.get('.manual-link').attributes()).toHaveProperty('disabled')
    expect(getCurrentPosition).not.toHaveBeenCalled()
    expect(getUserMedia).not.toHaveBeenCalled()
  })
})
