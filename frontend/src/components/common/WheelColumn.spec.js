/**
 * WheelColumn 계약 테스트 — PR #455 리뷰에서 지적된 회귀들.
 */
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import WheelColumn from '@/components/common/WheelColumn.vue'

const OPTIONS = [
  { label: '00', value: 0 },
  { label: '10', value: 10 },
  { label: '20', value: 20 }
]

describe('WheelColumn 접근성', () => {
  it('현재 선택된 항목만 aria-pressed 를 켠다', () => {
    const wrapper = mount(WheelColumn, { props: { options: OPTIONS, modelValue: 10 } })

    const buttons = wrapper.findAll('button')
    expect(buttons[1].attributes('aria-pressed')).toBe('true')
    expect(buttons[0].attributes('aria-pressed')).toBe('false')
    expect(buttons[2].attributes('aria-pressed')).toBe('false')
  })
})

describe('WheelColumn 키보드 탐색', () => {
  it('ArrowDown/ArrowUp 으로 값을 한 칸씩 옮기고 커밋한다', async () => {
    const wrapper = mount(WheelColumn, {
      props: { options: OPTIONS, modelValue: 10 },
      attachTo: document.body
    })

    const buttons = wrapper.findAll('button')
    await buttons[1].trigger('keydown', { key: 'ArrowDown' })

    expect(wrapper.emitted('update:modelValue').at(-1)).toEqual([20])
    wrapper.unmount()
  })

  it('맨 끝 항목에서는 더 옮기지 않는다', async () => {
    const wrapper = mount(WheelColumn, {
      props: { options: OPTIONS, modelValue: 20 },
      attachTo: document.body
    })

    const buttons = wrapper.findAll('button')
    await buttons[2].trigger('keydown', { key: 'ArrowDown' })

    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    wrapper.unmount()
  })
})

describe('WheelColumn 언마운트', () => {
  // 시트를 스크롤 직후 바로 닫으면(취소 등) 컴포넌트가 사라진다. 정착(settle) 타이머나
  // rAF 로 예약된 커밋이 언마운트 뒤에 실행돼 이미 사라진 컴포넌트를 향해
  // update:modelValue 를 emit 하면 안 된다.
  it('스크롤 직후 언마운트하면, 대기 중이던 커밋이 나중에 실행돼도 emit 하지 않는다', async () => {
    const wrapper = mount(WheelColumn, { props: { options: OPTIONS, modelValue: 0 } })

    wrapper.element.scrollTop = 44 // index 1(=10)로 스크롤
    await wrapper.trigger('scroll')
    wrapper.unmount()

    // settle 타이머(120ms)와 rAF 콜백이 실행될 시간을 충분히 준다.
    await new Promise((resolve) => setTimeout(resolve, 200))

    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })
})
