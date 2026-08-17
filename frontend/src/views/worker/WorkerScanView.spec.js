import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

vi.mock('@/services/worker', () => ({
  scan: vi.fn()
}))

import { scan } from '@/services/worker'
import WorkerScanView from '@/views/worker/WorkerScanView.vue'

/** `new BarcodeDetector()`로 호출되므로 화살표가 아닌 생성 가능한 함수여야 한다. */
function stubBarcodeDetector(detect) {
  window.BarcodeDetector = function BarcodeDetectorStub() {
    this.detect = detect
  }
  window.BarcodeDetector.getSupportedFormats = vi.fn().mockResolvedValue(['qr_code'])
  return detect
}

/** 각 호출이 독립 Track 목록을 주는 Stream Stub — Track 정리 여부를 호출별로 검증한다. */
function createStreamStub() {
  const track = { stop: vi.fn() }
  return { getTracks: () => [track], track }
}

function stubCapability({
  barcodeDetector = true,
  geolocation = true,
  mediaDevices = true,
  getUserMedia
} = {}) {
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true })

  if (mediaDevices) {
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: {
        getUserMedia: getUserMedia ?? vi.fn().mockResolvedValue(createStreamStub())
      }
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
    stubBarcodeDetector(vi.fn().mockResolvedValue([]))
  } else {
    delete window.BarcodeDetector
  }
}

describe('WorkerScanView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    scan.mockReset()
    push.mockReset()
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

  /**
   * 기록을 마친 사용자에게 다시 스캔 화면을 보여줄 이유가 없다. 결과 확인은 홈으로 보낸다.
   * 경로를 문자열로 고정한다 — 역할별 홈이 갈려 있어 사장 홈으로 새면 라우터 가드가 막는다.
   */
  it('결과 모달의 확인은 알바생 홈으로 이동한다', async () => {
    stubCapability()
    scan.mockResolvedValue({
      result: 'RECORDED',
      workCaseId: 1,
      scanType: 'CHECK_IN',
      recordedAt: '2026-08-12T01:00:00Z',
      isLate: false,
      lateMinutes: 0,
      earlyCheckoutConfirmedAt: null,
      settlementDueAt: null
    })

    const wrapper = mount(WorkerScanView)
    await flushPromises()
    await wrapper.vm.$.setupState.submitScan('qr-token')
    await flushPromises()

    expect(wrapper.vm.$.setupState.phase).toBe('result')

    // 모달은 Teleport 로 body 에 붙는다. 내부 함수 이름이 아니라 실제 버튼을 누른다.
    const confirmButton = [...document.querySelectorAll('.base-modal-footer .btn')].find(
      (button) => button.textContent.trim() === '확인'
    )
    expect(confirmButton).toBeTruthy()
    confirmButton.click()
    await flushPromises()

    expect(push).toHaveBeenCalledWith('/worker/home')
  })

  /**
   * 조기 퇴근 확인을 취소한 것은 아무것도 기록하지 않았다는 뜻이다. 홈으로 보내면
   * 기록이 끝난 것처럼 읽히므로 스캔 화면에 남긴다.
   */
  it('조기 퇴근 확인 취소는 홈으로 이동하지 않는다', async () => {
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

    wrapper.vm.$.setupState.cancelConfirmation()
    await flushPromises()

    expect(push).not.toHaveBeenCalled()
    expect(wrapper.vm.$.setupState.phase).toBe('idle')
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

  it('확인된 조기 퇴근은 결과 모달에 확인 시각을 표시한다', async () => {
    stubCapability()
    scan.mockResolvedValue({
      result: 'RECORDED',
      workCaseId: 1,
      scanType: 'CHECK_OUT',
      recordedAt: '2026-08-12T08:00:00Z',
      isLate: false,
      lateMinutes: 0,
      earlyCheckoutConfirmedAt: '2026-08-12T08:00:00Z',
      settlementDueAt: '2026-08-13T08:00:00Z'
    })

    const wrapper = mount(WorkerScanView)
    await flushPromises()
    await wrapper.vm.$.setupState.submitScan('qr-token', { confirmEarlyCheckout: true })
    await flushPromises()

    expect(wrapper.vm.$.setupState.phase).toBe('result')
    expect(document.querySelector('.result-early')?.textContent).toContain('조기 퇴근 확인')
  })

  it('503 일시 불가는 승인 Code 안내로 분기하고 같은 의도를 재사용하지 않는다', async () => {
    stubCapability()
    const error = new Error('unavailable')
    error.code = 'ATTENDANCE_TEMPORARILY_UNAVAILABLE'
    error.response = { status: 503 }
    scan.mockRejectedValueOnce(error)

    const wrapper = mount(WorkerScanView)
    await flushPromises()
    await wrapper.vm.$.setupState.submitScan('qr-token')
    await flushPromises()

    expect(wrapper.text()).toContain('일시적으로 처리할 수 없어요')
    expect(wrapper.text()).not.toContain('같은 요청 결과 다시 확인')
    expect(wrapper.vm.$.setupState.pendingIntent).toBeNull()
  })

  it('Code 없는 5xx는 결과가 불확실하므로 같은 의도를 보존한다', async () => {
    stubCapability()
    const error = new Error('bad gateway')
    error.response = { status: 502 }
    scan.mockRejectedValueOnce(error)

    const wrapper = mount(WorkerScanView)
    await flushPromises()
    await wrapper.vm.$.setupState.submitScan('qr-token')
    await flushPromises()

    expect(wrapper.text()).toContain('같은 요청 결과 다시 확인')
    expect(wrapper.vm.$.setupState.pendingIntent).not.toBeNull()
  })

  it('겹친 감지가 같은 QR을 반환해도 스캔을 정확히 한 번만 제출한다', async () => {
    let resolveDetect
    const detect = vi.fn(
      () =>
        new Promise((resolve) => {
          resolveDetect = resolve
        })
    )
    stubCapability()
    stubBarcodeDetector(detect)
    scan.mockResolvedValue({
      result: 'RECORDED',
      workCaseId: 1,
      scanType: 'CHECK_IN',
      recordedAt: '2026-08-12T01:00:00Z'
    })

    const wrapper = mount(WorkerScanView)
    await flushPromises()
    const setup = wrapper.vm.$.setupState
    await setup.startScan()
    await flushPromises()
    expect(setup.phase).toBe('scanning')

    // 앞선 detect()가 끝나기 전에 다음 감지가 시작되는 상황
    const first = setup.runDetect()
    const second = setup.runDetect()
    resolveDetect([{ rawValue: 'qr-token' }])
    await Promise.all([first, second])
    await flushPromises()

    expect(detect).toHaveBeenCalledTimes(1)
    expect(scan).toHaveBeenCalledTimes(1)
  })

  it('409 CONFLICT는 처리 중이므로 같은 Key를 보존해 재확인한다', async () => {
    stubCapability()
    const error = new Error('conflict')
    error.code = 'CONFLICT'
    error.response = { status: 409 }
    scan.mockRejectedValueOnce(error)

    const wrapper = mount(WorkerScanView)
    await flushPromises()
    await wrapper.vm.$.setupState.submitScan('qr-token')
    await flushPromises()

    expect(wrapper.text()).toContain('같은 요청을 처리하고 있어요')
    expect(wrapper.text()).toContain('같은 요청 결과 다시 확인')
    expect(wrapper.vm.$.setupState.pendingIntent).not.toBeNull()

    const firstCallArgs = scan.mock.calls[0][0]
    scan.mockResolvedValueOnce({
      result: 'RECORDED',
      workCaseId: 1,
      scanType: 'CHECK_IN',
      recordedAt: '2026-08-12T01:00:00Z'
    })

    await wrapper.vm.$.setupState.retryPendingIntent()
    await flushPromises()

    expect(scan).toHaveBeenLastCalledWith(
      expect.objectContaining({ idempotencyKey: firstCallArgs.idempotencyKey })
    )
  })

  it.each(['IDEMPOTENCY_KEY_REUSED', 'ATTENDANCE_STATE_CONFLICT'])(
    '확정 충돌 %s은 같은 의도를 보존하지 않는다',
    async (code) => {
      stubCapability()
      const error = new Error(code)
      error.code = code
      error.response = { status: 409 }
      scan.mockRejectedValueOnce(error)

      const wrapper = mount(WorkerScanView)
      await flushPromises()
      await wrapper.vm.$.setupState.submitScan('qr-token')
      await flushPromises()

      expect(wrapper.vm.$.setupState.pendingIntent).toBeNull()
      expect(wrapper.text()).not.toContain('같은 요청 결과 다시 확인')
    }
  )

  it('스캔 시작 중복 클릭은 위치·카메라를 한 번만 요청한다', async () => {
    let resolveMedia
    const streamStub = createStreamStub()
    const getUserMedia = vi.fn(
      () =>
        new Promise((resolve) => {
          resolveMedia = resolve
        })
    )
    stubCapability({ getUserMedia })

    const wrapper = mount(WorkerScanView)
    await flushPromises()
    const setup = wrapper.vm.$.setupState

    const first = setup.startScan()
    await flushPromises()
    const second = setup.startScan()
    resolveMedia(streamStub)
    await Promise.all([first, second])
    await flushPromises()

    expect(navigator.geolocation.getCurrentPosition).toHaveBeenCalledTimes(1)
    expect(getUserMedia).toHaveBeenCalledTimes(1)
    expect(setup.phase).toBe('scanning')
  })

  it('취소와 화면 이탈에서 Camera Track을 정리한다', async () => {
    const streamStub = createStreamStub()
    stubCapability({ getUserMedia: vi.fn().mockResolvedValue(streamStub) })

    const wrapper = mount(WorkerScanView)
    await flushPromises()
    const setup = wrapper.vm.$.setupState
    await setup.startScan()
    await flushPromises()

    setup.cancelScan()
    expect(streamStub.track.stop).toHaveBeenCalledTimes(1)
    expect(setup.phase).toBe('idle')

    wrapper.unmount()
    expect(streamStub.track.stop).toHaveBeenCalledTimes(1)
  })

  it('스캔 시작 대기 중 화면을 벗어나면 뒤늦게 열린 Track도 정리한다', async () => {
    let resolveMedia
    const streamStub = createStreamStub()
    stubCapability({
      getUserMedia: vi.fn(
        () =>
          new Promise((resolve) => {
            resolveMedia = resolve
          })
      )
    })

    const wrapper = mount(WorkerScanView)
    await flushPromises()
    const pending = wrapper.vm.$.setupState.startScan()
    await flushPromises()

    wrapper.unmount()
    resolveMedia(streamStub)
    await pending
    await flushPromises()

    expect(streamStub.track.stop).toHaveBeenCalledTimes(1)
  })

  it('오래된 위치 측정값은 제출 직전에 다시 얻는다', async () => {
    stubCapability()
    const staleAt = Date.now() - 5 * 60 * 1000
    const freshAt = Date.now()
    const getCurrentPosition = vi
      .fn()
      .mockImplementationOnce((resolve) =>
        resolve({ coords: { latitude: 37.5, longitude: 127.5, accuracy: 10 }, timestamp: staleAt })
      )
      .mockImplementationOnce((resolve) =>
        resolve({ coords: { latitude: 37.6, longitude: 127.6, accuracy: 8 }, timestamp: freshAt })
      )
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: { getCurrentPosition }
    })
    scan.mockResolvedValue({
      result: 'RECORDED',
      workCaseId: 1,
      scanType: 'CHECK_IN',
      recordedAt: '2026-08-12T01:00:00Z'
    })

    const wrapper = mount(WorkerScanView)
    await flushPromises()
    const setup = wrapper.vm.$.setupState
    await setup.startScan()
    await flushPromises()
    await setup.submitScan('qr-token')
    await flushPromises()

    expect(getCurrentPosition).toHaveBeenCalledTimes(2)
    expect(scan).toHaveBeenCalledWith(
      expect.objectContaining({ capturedAt: new Date(freshAt).toISOString(), latitude: 37.6 })
    )
  })

  it('신선한 위치 측정값은 다시 얻지 않는다', async () => {
    stubCapability()
    scan.mockResolvedValue({
      result: 'RECORDED',
      workCaseId: 1,
      scanType: 'CHECK_IN',
      recordedAt: '2026-08-12T01:00:00Z'
    })

    const wrapper = mount(WorkerScanView)
    await flushPromises()
    const setup = wrapper.vm.$.setupState
    await setup.startScan()
    await flushPromises()
    await setup.submitScan('qr-token')
    await flushPromises()

    expect(navigator.geolocation.getCurrentPosition).toHaveBeenCalledTimes(1)
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
