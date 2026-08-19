import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import LoadingOverlay from '@/components/common/LoadingOverlay.vue'
import { useUiStore } from '@/stores/ui'

function mountOverlay() {
  return mount(LoadingOverlay, { global: { stubs: { teleport: true } } })
}

describe('LoadingOverlay', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('로딩 중이 아니면 아무것도 보이지 않는다', () => {
    const wrapper = mountOverlay()

    expect(wrapper.find('.loading-overlay').exists()).toBe(false)
  })

  it('startLoading 이 전달한 메시지를 보여준다', async () => {
    const wrapper = mountOverlay()
    useUiStore().startLoading('충전을 처리하고 있어요…')
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('충전을 처리하고 있어요…')
  })

  describe('최소 노출 시간', () => {
    beforeEach(() => vi.useFakeTimers())
    afterEach(() => vi.useRealTimers())

    it('stopLoading 을 바로 불러도 최소 0.5초는 계속 떠 있는다', async () => {
      const wrapper = mountOverlay()
      const ui = useUiStore()

      ui.startLoading()
      await wrapper.vm.$nextTick()
      ui.stopLoading()
      await wrapper.vm.$nextTick()
      expect(wrapper.find('.loading-overlay').exists()).toBe(true)

      await vi.advanceTimersByTimeAsync(499)
      expect(wrapper.find('.loading-overlay').exists()).toBe(true)

      await vi.advanceTimersByTimeAsync(1)
      expect(wrapper.find('.loading-overlay').exists()).toBe(false)
    })

    it('0.5초 넘게 떠 있었다면 stopLoading 즉시 사라진다', async () => {
      const wrapper = mountOverlay()
      const ui = useUiStore()

      ui.startLoading()
      await wrapper.vm.$nextTick()
      await vi.advanceTimersByTimeAsync(600)
      ui.stopLoading()
      await wrapper.vm.$nextTick()

      expect(wrapper.find('.loading-overlay').exists()).toBe(false)
    })

    it('stopLoading을 연속으로 불러도 이전 타이머가 다음 로딩을 끄지 않는다', async () => {
      // stopLoading 을 두 번 부르면(예: 재시도 흐름) 두 타이머가 같은 목표 시각(t=500)을
      // 겨눈다. 정리 없이 두 번째로 덮어쓰면 첫 타이머(A)가 고아로 남아, 그 시각에 시작한
      // 지 얼마 안 된 다음 로딩 세션을 뒤늦게 꺼버릴 수 있다.
      const wrapper = mountOverlay()
      const ui = useUiStore()

      ui.startLoading() // 세션1 시작 (t=0)
      await wrapper.vm.$nextTick()
      ui.stopLoading() // 타이머 A 예약 (목표 t=500)
      ui.stopLoading() // 타이머 B 예약 (같은 목표 t=500) — 수정 전이라면 A가 고아로 남는다

      await vi.advanceTimersByTimeAsync(200) // 아직 500ms 전 — A·B 모두 대기 중
      ui.startLoading() // 세션2 시작 (t=200)
      await wrapper.vm.$nextTick()

      await vi.advanceTimersByTimeAsync(300) // 절대 시각 t=500 — A·B 의 옛 목표 시각 도달
      // 수정 전이면 정리되지 않은 A가 여기서 발동해, 막 시작한 세션2(아직 300ms 밖에
      // 안 지남)를 꺼버린다.
      expect(wrapper.find('.loading-overlay').exists()).toBe(true)
    })
  })
})
