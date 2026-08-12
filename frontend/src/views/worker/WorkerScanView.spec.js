import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/services/worker', () => ({
  scan: vi.fn()
}))

import { scan } from '@/services/worker'
import WorkerScanView from '@/views/worker/WorkerScanView.vue'

function stubCapability({ barcodeDetector = true, geolocation = true, mediaDevices = true } = {}) {
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true })

  if (mediaDevices) {
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: vi.fn().mockResolvedValue({ getTracks: () => [] }) }
    })
  } else {
    Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: undefined })
  }

  if (geolocation) {
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: {
        getCurrentPosition: vi.fn((resolve) =>
          resolve({
            coords: { latitude: 37.5, longitude: 127.5, accuracy: 10 },
            timestamp: Date.now()
          })
        )
      }
    })
  } else {
    Object.defineProperty(navigator, 'geolocation', { configurable: true, value: undefined })
  }

  if (barcodeDetector) {
    window.BarcodeDetector = vi
      .fn()
      .mockImplementation(() => ({ detect: vi.fn().mockResolvedValue([]) }))
    window.BarcodeDetector.getSupportedFormats = vi.fn().mockResolvedValue(['qr_code'])
  } else {
    delete window.BarcodeDetector
  }
}

describe('WorkerScanView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    scan.mockReset()
  })

  afterEach(() => {
    delete window.BarcodeDetector
  })

  it('Capability 미지원 환경은 안내만 표시하고 위치·카메라 권한을 요청하지 않는다', async () => {
    stubCapability({ barcodeDetector: false })

    const wrapper = mount(WorkerScanView)
    await flushPromises()

    expect(wrapper.text()).toContain('지원하지 않아요')
    expect(wrapper.get('.btn').attributes()).toHaveProperty('disabled')
    expect(navigator.geolocation.getCurrentPosition).not.toHaveBeenCalled()
    expect(navigator.mediaDevices.getUserMedia).not.toHaveBeenCalled()
  })

  it('QR 토큰 직접 입력 UI를 제공하지 않는다', async () => {
    stubCapability()
    const wrapper = mount(WorkerScanView)
    await flushPromises()

    expect(wrapper.find('input').exists()).toBe(false)
  })

  it('지각 CHECK_IN 성공 시 결과 모달에 지각 분수를 표시한다', async () => {
    stubCapability()
    scan.mockResolvedValue({
      result: 'RECORDED',
      workCaseId: 1,
      scanType: 'CHECK_IN',
      recordedAt: '2026-08-12T01:00:00Z',
      isLate: true,
      lateMinutes: 5,
      earlyCheckoutConfirmedAt: null,
      settlementDueAt: null
    })

    const wrapper = mount(WorkerScanView)
    await flushPromises()
    await wrapper.vm.$.setupState.submitScan('qr-token')
    await flushPromises()

    expect(document.body.textContent).toContain('지각 5분으로 기록됨')
    expect(scan).toHaveBeenCalledWith(
      expect.objectContaining({ qrToken: 'qr-token', confirmEarlyCheckout: false })
    )
  })

  it('CONFIRMATION_REQUIRED는 실패가 아니라 확인 모달로 전환하고, 취소 시 상태를 갱신하지 않는다', async () => {
    stubCapability()
    scan.mockResolvedValue({
      result: 'CONFIRMATION_REQUIRED',
      workCaseId: 1,
      scanType: 'CHECK_OUT',
      scheduledEndAt: '2026-08-12T09:00:00Z'
    })

    const wrapper = mount(WorkerScanView)
    await flushPromises()
    await wrapper.vm.$.setupState.submitScan('qr-token')
    await flushPromises()

    expect(document.body.textContent).toContain('조기 퇴근 확인')

    wrapper.vm.$.setupState.cancelConfirmation()
    await flushPromises()

    expect(wrapper.vm.$.setupState.phase).toBe('idle')
    expect(wrapper.vm.$.setupState.result).toBeNull()
  })

  it('응답 유실(네트워크 오류)은 같은 의도를 보존해 재확인 버튼을 제공한다', async () => {
    stubCapability()
    scan.mockRejectedValueOnce(new Error('network down'))

    const wrapper = mount(WorkerScanView)
    await flushPromises()
    await wrapper.vm.$.setupState.submitScan('qr-token')
    await flushPromises()

    expect(wrapper.text()).toContain('같은 요청으로 결과를 다시 확인해주세요')
    expect(wrapper.text()).toContain('같은 요청 결과 다시 확인')

    const firstCallArgs = scan.mock.calls[0][0]
    scan.mockResolvedValueOnce({
      result: 'RECORDED',
      workCaseId: 1,
      scanType: 'CHECK_IN',
      recordedAt: '2026-08-12T01:00:00Z',
      isLate: false,
      lateMinutes: 0,
      earlyCheckoutConfirmedAt: null,
      settlementDueAt: null
    })

    await wrapper.vm.$.setupState.retryPendingIntent()
    await flushPromises()

    expect(scan).toHaveBeenLastCalledWith(
      expect.objectContaining({ idempotencyKey: firstCallArgs.idempotencyKey })
    )
  })

  it('폐기 QR(410)은 안내 문구로 분기하고 같은 의도를 재사용하지 않는다', async () => {
    stubCapability()
    const error = new Error('revoked')
    error.code = 'QR_REVOKED'
    error.response = { status: 410 }
    scan.mockRejectedValueOnce(error)

    const wrapper = mount(WorkerScanView)
    await flushPromises()
    await wrapper.vm.$.setupState.submitScan('qr-token')
    await flushPromises()

    expect(wrapper.text()).toContain('더 이상 사용하지 않는 QR')
    expect(wrapper.vm.$.setupState.pendingIntent).toBeNull()
  })
})
