/**
 * 사장 QR 화면의 재발급 방어 계약 테스트.
 *
 * 재발급은 승인 계약상 멱등 Header 를 쓰지 않는다(DEC-OPEN-QR-REISSUE-IDEMPOTENCY).
 * 그래서 이중 제출을 막는 것은 오직 이 화면의 확인 단계와 전송 중 잠금뿐이다.
 * 두 번 보내면 첫 응답으로 인쇄한 QR 이 두 번째 요청에 폐기된다.
 *
 * 활성 QR 이 없는 지점은 조회가 500 으로 실패하는데, 그 상태에서 사용자가 스스로 복구할
 * 수 있는 유일한 경로가 재발급이다 — 버튼이 조회 성공에 묶이면 복구가 불가능해진다.
 */
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/services/workplaces', () => ({
  getWorkplaceQr: vi.fn(),
  reissueWorkplaceQr: vi.fn()
}))

vi.mock('qrcode', () => ({ default: { toCanvas: vi.fn().mockResolvedValue(undefined) } }))

import QRCode from 'qrcode'

import { getWorkplaceQr, reissueWorkplaceQr } from '@/services/workplaces'
import { useWorkplaceStore } from '@/stores/workplace'
import OwnerQrView from '@/views/owner/OwnerQrView.vue'

/** Teleport 를 stub 해 확인 Modal 내용을 wrapper 안에서 찾을 수 있게 한다. */
function mountView() {
  return mount(OwnerQrView, { global: { stubs: { teleport: true } } })
}

function reissueButton(wrapper) {
  return wrapper.findAll('button').find((button) => button.text().includes('QR 재발급'))
}

function confirmButton(wrapper) {
  return wrapper
    .findAll('button')
    .find((button) => button.text().includes('재발급') && !button.text().includes('QR 재발급'))
}

describe('OwnerQrView 재발급', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()

    const store = useWorkplaceStore()
    // hasActiveWorkplace 가 false 면 화면이 EmptyState 로 갈라져 재발급 UI 자체가 없다.
    store.workplaces = [{ workplaceId: 7, name: '강남점', status: 'ACTIVE' }]
    store.selectedId = 7
    store.loaded = true
    store.load = vi.fn()

    getWorkplaceQr.mockResolvedValue({ workplaceId: 7, qrToken: 'v1.k1.7.old.mac' })
    reissueWorkplaceQr.mockResolvedValue({ workplaceId: 7, qrToken: 'v1.k1.7.new.mac' })
  })

  /**
   * qrcode 는 그리면서 canvas 에 style.width/height 를 옵션 width(512px)로 직접 박는다
   * (lib/renderer/canvas.js 의 clearCanvas). 인라인 스타일이라 scoped CSS 를 이기고,
   * 그대로 두면 QR 이 512px 로 상자를 뚫거나 max-width 로 폭만 눌려 세로로 늘어난다.
   * 표시 크기는 CSS 가 정해야 하므로 그리기 직후 인라인 값이 남아 있으면 안 된다.
   */
  it('그리기 후 canvas 의 인라인 크기 스타일을 남기지 않는다', async () => {
    QRCode.toCanvas.mockImplementation(async (canvas) => {
      canvas.style.width = '512px'
      canvas.style.height = '512px'
    })

    const wrapper = mountView()
    await flushPromises()

    const canvas = wrapper.get('canvas').element
    expect(canvas.style.width).toBe('')
    expect(canvas.style.height).toBe('')
  })

  it('버튼만 눌러서는 재발급이 실행되지 않는다', async () => {
    const wrapper = mountView()
    await flushPromises()

    await reissueButton(wrapper).trigger('click')
    await flushPromises()

    // 확인 단계를 건너뛰면 오조작 한 번이 벽에 붙은 QR 을 죽인다.
    expect(reissueWorkplaceQr).not.toHaveBeenCalled()
  })

  it('확인해야 재발급하고 새 토큰을 반영한다', async () => {
    const wrapper = mountView()
    await flushPromises()

    await reissueButton(wrapper).trigger('click')
    await confirmButton(wrapper).trigger('click')
    await flushPromises()

    expect(reissueWorkplaceQr).toHaveBeenCalledWith(7)
    expect(wrapper.text()).toContain('v1.k1.7.new.mac')
  })

  it('전송이 끝나기 전 다시 눌러도 한 번만 보낸다', async () => {
    let resolveReissue
    reissueWorkplaceQr.mockReturnValue(
      new Promise((resolve) => {
        resolveReissue = resolve
      })
    )

    const wrapper = mountView()
    await flushPromises()

    await reissueButton(wrapper).trigger('click')
    const confirm = confirmButton(wrapper)
    await confirm.trigger('click')
    await confirm.trigger('click')
    await confirm.trigger('click')

    expect(reissueWorkplaceQr).toHaveBeenCalledTimes(1)

    resolveReissue({ workplaceId: 7, qrToken: 'v1.k1.7.new.mac' })
    await flushPromises()
  })

  it('조회가 실패해 QR 이 없어도 재발급 버튼을 누를 수 있다', async () => {
    getWorkplaceQr.mockRejectedValue(new Error('500'))

    const wrapper = mountView()
    await flushPromises()

    // 활성 QR 이 없는 지점의 유일한 복구 경로다.
    expect(wrapper.text()).toContain('표시할 QR이 없어요.')
    expect(reissueButton(wrapper)).toBeTruthy()

    await reissueButton(wrapper).trigger('click')
    await confirmButton(wrapper).trigger('click')
    await flushPromises()

    expect(reissueWorkplaceQr).toHaveBeenCalledWith(7)
  })
})
