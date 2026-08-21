import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'

import { useClickTogglePopover } from '@/composables/useClickTogglePopover'

/** 컴포저블은 생명주기 훅(onMounted/onUnmounted)을 쓰므로 호스트 컴포넌트 안에서 실행한다. */
function mountPopover() {
  let api
  const Host = defineComponent({
    setup() {
      api = useClickTogglePopover()
      return () =>
        h('div', { ref: api.rootEl }, [
          h('button', { onClick: api.toggle }, 'i'),
          h('span', { class: 'inside' }, 'popover content')
        ])
    }
  })
  const wrapper = mount(Host, { attachTo: document.body })
  return { wrapper, api }
}

describe('useClickTogglePopover', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('toggle을 부를 때마다 open이 뒤집힌다', () => {
    const { api } = mountPopover()

    expect(api.open.value).toBe(false)
    api.toggle()
    expect(api.open.value).toBe(true)
    api.toggle()
    expect(api.open.value).toBe(false)
  })

  it('rootEl 바깥을 클릭하면 닫힌다', async () => {
    const { wrapper, api } = mountPopover()
    api.toggle()
    expect(api.open.value).toBe(true)

    document.body.dispatchEvent(new window.MouseEvent('click', { bubbles: true }))
    await wrapper.vm.$nextTick()

    expect(api.open.value).toBe(false)
  })

  it('rootEl 안쪽을 클릭하면 닫히지 않는다', async () => {
    const { wrapper, api } = mountPopover()
    api.toggle()

    wrapper.get('.inside').element.dispatchEvent(new window.MouseEvent('click', { bubbles: true }))
    await wrapper.vm.$nextTick()

    expect(api.open.value).toBe(true)
  })

  it('Escape를 누르면 닫힌다', async () => {
    const { wrapper, api } = mountPopover()
    api.toggle()

    document.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape' }))
    await wrapper.vm.$nextTick()

    expect(api.open.value).toBe(false)
  })

  it('close를 부르면 열려 있어도 닫힌다', () => {
    const { api } = mountPopover()
    api.toggle()

    api.close()

    expect(api.open.value).toBe(false)
  })

  it('unmount 시 document 리스너를 정리한다(메모리 누수 방지)', () => {
    const removeSpy = vi.spyOn(document, 'removeEventListener')
    const { wrapper } = mountPopover()

    wrapper.unmount()

    expect(removeSpy).toHaveBeenCalledWith('click', expect.any(Function))
    expect(removeSpy).toHaveBeenCalledWith('keydown', expect.any(Function))
    removeSpy.mockRestore()
  })
})
