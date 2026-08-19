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
  })
})
