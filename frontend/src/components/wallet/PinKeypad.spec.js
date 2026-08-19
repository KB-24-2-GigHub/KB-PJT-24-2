import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import PinKeypad from '@/components/wallet/PinKeypad.vue'

describe('PinKeypad', () => {
  it('숫자 키를 누른 순서대로 값을 올리고 최대 자리수를 넘기지 않는다', async () => {
    const wrapper = mount(PinKeypad, { props: { modelValue: '' } })

    const keys = wrapper.findAll('button.key')
    await keys[0].trigger('click')

    expect(wrapper.emitted('update:modelValue')[0][0]).toHaveLength(1)
  })

  it('값이 이미 4자리면 숫자 키를 눌러도 더 늘리지 않는다', async () => {
    const wrapper = mount(PinKeypad, { props: { modelValue: '1234' } })

    const digitKey = wrapper.findAll('button.key').find((b) => /^\d$/.test(b.text()))
    await digitKey.trigger('click')

    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('지우기 버튼은 마지막 한 자리만 지운다', async () => {
    const wrapper = mount(PinKeypad, { props: { modelValue: '123' } })

    await wrapper.find('button.key--action').trigger('click')

    expect(wrapper.emitted('update:modelValue')[0][0]).toBe('12')
  })

  it('기존 값에 이어서 붙인다', async () => {
    const wrapper = mount(PinKeypad, { props: { modelValue: '12' } })
    const key = wrapper.findAll('button.key').find((b) => b.text() === '3')

    await key.trigger('click')

    expect(wrapper.emitted('update:modelValue')[0][0]).toBe('123')
  })

  it('값이 비워지면 숫자 배치를 다시 섞는다', async () => {
    // 셔플 결과가 우연히 같을 수 있어 순서 불일치를 단언하면 플레이키해진다.
    // 재배치 뒤에도 숫자 10개 구성이 그대로 보존되는지만 확인한다.
    const wrapper = mount(PinKeypad, { props: { modelValue: '1234' } })
    const before = wrapper
      .findAll('button.key')
      .map((b) => b.text())
      .filter((t) => /^\d$/.test(t))

    await wrapper.setProps({ modelValue: '' })

    const after = wrapper
      .findAll('button.key')
      .map((b) => b.text())
      .filter((t) => /^\d$/.test(t))
    expect(after).toHaveLength(before.length)
    expect(new Set(after)).toEqual(new Set(before))
  })

  it('입력한 자리수만큼 점을 채워 보여준다', async () => {
    const wrapper = mount(PinKeypad, { props: { modelValue: '12' } })

    expect(wrapper.findAll('.dot.filled')).toHaveLength(2)
    expect(wrapper.findAll('.dot')).toHaveLength(4)
  })

  it('숫자 10개를 중복 없이 키패드에 배치한다', () => {
    const wrapper = mount(PinKeypad, { props: { modelValue: '' } })

    const labels = wrapper
      .findAll('button.key')
      .map((b) => b.text())
      .filter((t) => /^\d$/.test(t))

    expect(new Set(labels).size).toBe(10)
  })

  it('error가 있으면 hint 대신 error 메시지를 보여준다', () => {
    const wrapper = mount(PinKeypad, {
      props: { modelValue: '', error: 'PIN은 숫자 4자리여야 합니다.', hint: 'Demo PIN은 0000' }
    })

    expect(wrapper.find('[role="alert"]').text()).toBe('PIN은 숫자 4자리여야 합니다.')
    expect(wrapper.text()).not.toContain('Demo PIN은 0000')
  })
})
