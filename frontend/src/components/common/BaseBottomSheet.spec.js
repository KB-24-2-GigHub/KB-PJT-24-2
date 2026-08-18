import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import { nextTick } from 'vue'

import BaseBottomSheet from '@/components/common/BaseBottomSheet.vue'

/**
 * 바텀시트는 aria-modal 을 달고 있다. 그 선언을 지키려면 열려 있는 동안 키보드와 포커스가
 * 시트 안에 머물러야 한다. 선언만 있고 가둠이 없으면 스크린리더 사용자는 시트가 열린 줄
 * 모른 채 뒤 화면을 읽는다.
 */
function mountSheet(props = {}) {
  return mount(BaseBottomSheet, {
    props: { open: true, title: '필터', ...props },
    slots: {
      default: '<button class="first">첫 항목</button><button class="last">끝 항목</button>'
    },
    // Teleport 를 그대로 두면 시트가 wrapper 밖(body 직속)으로 나가 find 로 잡히지 않는다.
    global: { stubs: { teleport: true } },
    // 포커스는 문서에 붙어 있는 요소에만 들어간다.
    attachTo: document.body
  })
}

afterEach(() => {
  document.body.style.overflow = ''
})

describe('BaseBottomSheet 포커스와 키보드', () => {
  it('열리면 시트로 포커스를 옮긴다', async () => {
    const wrapper = mountSheet()
    await nextTick()

    expect(document.activeElement).toBe(wrapper.find('.sheet').element)

    wrapper.unmount()
  })

  it('Escape 를 누르면 닫아 달라고 알린다', async () => {
    const wrapper = mountSheet()
    await nextTick()

    await wrapper.find('.sheet').trigger('keydown', { key: 'Escape' })

    expect(wrapper.emitted('close')).toHaveLength(1)

    wrapper.unmount()
  })

  /* 문서 순서상 첫 포커스 대상은 헤더의 닫기 버튼, 마지막은 본문의 끝 항목이다. */
  it('마지막 요소에서 Tab 을 누르면 시트 밖으로 나가지 않고 처음으로 돌아온다', async () => {
    const wrapper = mountSheet()
    await nextTick()

    wrapper.find('.last').element.focus()
    await wrapper.find('.sheet').trigger('keydown', { key: 'Tab' })

    expect(document.activeElement).toBe(wrapper.find('.close').element)

    wrapper.unmount()
  })

  it('첫 요소에서 Shift+Tab 을 누르면 마지막으로 감싼다', async () => {
    const wrapper = mountSheet()
    await nextTick()

    wrapper.find('.close').element.focus()
    await wrapper.find('.sheet').trigger('keydown', { key: 'Tab', shiftKey: true })

    expect(document.activeElement).toBe(wrapper.find('.last').element)

    wrapper.unmount()
  })

  /* 열자마자(시트 자체에 포커스가 있는 상태) Shift+Tab 도 밖으로 새면 안 된다. */
  it('시트에 포커스가 있는 채로 Shift+Tab 을 눌러도 밖으로 나가지 않는다', async () => {
    const wrapper = mountSheet()
    await nextTick()

    await wrapper.find('.sheet').trigger('keydown', { key: 'Tab', shiftKey: true })

    expect(document.activeElement).toBe(wrapper.find('.last').element)

    wrapper.unmount()
  })

  it('제목이 시트의 이름이 되게 연결한다', () => {
    const wrapper = mountSheet()

    const labelledBy = wrapper.find('.sheet').attributes('aria-labelledby')

    expect(labelledBy).toBeTruthy()
    expect(wrapper.find(`#${labelledBy}`).text()).toBe('필터')

    wrapper.unmount()
  })
})

describe('BaseBottomSheet 배경 스크롤', () => {
  it('열려 있는 동안 뒤 화면 스크롤을 막고 닫히면 되돌린다', async () => {
    const wrapper = mountSheet({ open: false })
    expect(document.body.style.overflow).toBe('')

    await wrapper.setProps({ open: true })
    expect(document.body.style.overflow).toBe('hidden')

    await wrapper.setProps({ open: false })
    expect(document.body.style.overflow).toBe('')

    wrapper.unmount()
  })

  /* 시트가 열린 채로 화면이 사라지면(라우팅) 잠금이 남아 뒤 화면이 영영 안 굴러간다. */
  it('열린 채로 사라져도 스크롤 잠금을 남기지 않는다', async () => {
    const wrapper = mountSheet()
    expect(document.body.style.overflow).toBe('hidden')

    wrapper.unmount()

    expect(document.body.style.overflow).toBe('')
  })
})
