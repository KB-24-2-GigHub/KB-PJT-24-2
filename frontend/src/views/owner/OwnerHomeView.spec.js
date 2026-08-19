import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import OwnerHomeView from '@/views/owner/OwnerHomeView.vue'

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/services/wallet', () => ({
  fetchWallet: vi
    .fn()
    .mockResolvedValue({ currency: 'KRW', availableBalance: 0, lockedBalance: 480_000 }),
  fetchTransactions: vi.fn().mockResolvedValue({
    content: [],
    page: { number: 0, size: 20, totalElements: 0, totalPages: 0 }
  })
}))

function findHeldInfoButton(wrapper) {
  return wrapper.find('button.held-info')
}

describe('OwnerHomeView 예치중 안내', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('물음표 아이콘을 누르면 안내가 열리고 다시 누르면 닫힌다', async () => {
    const wrapper = mount(OwnerHomeView)
    await flushPromises()

    expect(wrapper.find('.held-popover').exists()).toBe(false)

    await findHeldInfoButton(wrapper).trigger('click')
    expect(wrapper.find('.held-popover').exists()).toBe(true)
    expect(wrapper.find('.held-popover').text()).toContain('에스크로')

    await findHeldInfoButton(wrapper).trigger('click')
    expect(wrapper.find('.held-popover').exists()).toBe(false)
  })

  it('바깥을 클릭하면 안내가 닫힌다', async () => {
    const wrapper = mount(OwnerHomeView, { attachTo: document.body })
    await flushPromises()

    await findHeldInfoButton(wrapper).trigger('click')
    expect(wrapper.find('.held-popover').exists()).toBe(true)

    document.body.dispatchEvent(new window.MouseEvent('click', { bubbles: true }))
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.held-popover').exists()).toBe(false)
    wrapper.unmount()
  })

  it('Escape 로도 안내가 닫힌다', async () => {
    const wrapper = mount(OwnerHomeView)
    await flushPromises()

    await findHeldInfoButton(wrapper).trigger('click')
    expect(wrapper.find('.held-popover').exists()).toBe(true)

    document.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape' }))
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.held-popover').exists()).toBe(false)
  })
})
