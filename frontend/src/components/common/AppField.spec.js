/**
 * AppField 계약 테스트.
 * digits-only 필드는 한글 IME 조합을 취소하려고 내부적으로 blur/focus 를 호출한다.
 * 그 내부 blur 가 부모로 새어 나가면 전화번호 칸에 한글을 치는 순간 필드가 touched 가
 * 되어 입력 중에 검증이 돈다 — 실시간 검증 계약의 "입력 중 조용함"이 깨진다.
 */
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import AppField from '@/components/common/AppField.vue'

describe('AppField blur', () => {
  it('사용자가 필드를 떠나면 blur 를 올린다', async () => {
    const wrapper = mount(AppField, { props: { label: '이메일' } })

    await wrapper.find('input').trigger('blur')

    expect(wrapper.emitted('blur')).toHaveLength(1)
  })

  // jsdom 은 el.blur() 를 실제 document.activeElement 에서만 blur 이벤트로 반영한다.
  // attachTo 없이 mount 만 하면 input 이 activeElement 가 되지 않아 compositionstart 핸들러
  // 내부의 el.blur() 가 아무 이벤트도 못 올리는 조용한 무동작이 된다 — 그러면 이 테스트는
  // 구현을 지워도 항상 통과하는 껍데기가 된다. document.body 에 붙이고 실제로 focus 시켜
  // el.blur() 가 진짜 blur 이벤트를 올리는 조합 취소 구간을 재현한다.
  it('IME 조합 취소로 생기는 내부 blur 는 올리지 않는다', async () => {
    const wrapper = mount(AppField, {
      props: { label: '전화번호', digitsOnly: true },
      attachTo: document.body
    })
    const input = wrapper.find('input')
    input.element.focus()
    expect(document.activeElement).toBe(input.element)

    // 조합 시작 → 컴포넌트가 el.blur(); el.focus() 로 조합을 취소한다.
    await input.trigger('compositionstart')

    expect(wrapper.emitted('blur')).toBeUndefined()
    wrapper.unmount()
  })

  it('조합 취소 뒤에도 진짜 blur 는 다시 올린다', async () => {
    const wrapper = mount(AppField, {
      props: { label: '전화번호', digitsOnly: true },
      attachTo: document.body
    })
    const input = wrapper.find('input')
    input.element.focus()

    await input.trigger('compositionstart')
    await input.trigger('blur')

    expect(wrapper.emitted('blur')).toHaveLength(1)
    wrapper.unmount()
  })
})

describe('AppField success', () => {
  it('success 를 주면 문구를 보여준다', () => {
    const wrapper = mount(AppField, {
      props: { label: '아이디', success: '사용 가능한 아이디입니다' }
    })

    expect(wrapper.text()).toContain('사용 가능한 아이디입니다')
  })

  it('error 가 있으면 success 보다 error 를 보여준다', () => {
    const wrapper = mount(AppField, {
      props: { label: '아이디', success: '사용 가능합니다', error: '이미 사용 중입니다' }
    })

    expect(wrapper.text()).toContain('이미 사용 중입니다')
    expect(wrapper.text()).not.toContain('사용 가능합니다')
  })
})

describe('AppField 모델 동기화', () => {
  it('부모가 값을 비우면 실제 DOM 입력값도 지운다', async () => {
    const wrapper = mount(AppField, { props: { modelValue: '0000', type: 'password' } })

    await wrapper.setProps({ modelValue: '' })

    expect(wrapper.find('input').element.value).toBe('')
  })
})

// F3: 이 브랜치(#238)가 blur 시 뜨는 메시지를 주된 피드백 채널로 만들었으니, 스크린리더
// 사용자에게도 같은 신호가 가야 한다. 메시지는 input 의 형제 <p> 로만 렌더되고 있었다 —
// DOM 상 연결이 없으면 시각적으로만 오류가 "보이고" 스크린리더는 아무 것도 알리지 않는다.
describe('AppField 접근성', () => {
  it('오류가 있으면 input 이 aria-describedby 로 메시지를 가리키고 aria-invalid 를 켠다', () => {
    const wrapper = mount(AppField, { props: { label: '아이디', error: '이미 사용 중입니다' } })

    const input = wrapper.find('input')
    const describedBy = input.attributes('aria-describedby')
    expect(describedBy).toBeTruthy()
    expect(input.attributes('aria-invalid')).toBe('true')

    // aria-describedby 가 가리키는 id 가 실제로 그 메시지 엘리먼트의 id 여야 한다 —
    // 값만 있고 대상이 없으면 스크린리더가 아무 것도 읽지 못한다.
    const msg = wrapper.find(`#${describedBy}`)
    expect(msg.exists()).toBe(true)
    expect(msg.text()).toBe('이미 사용 중입니다')
    expect(msg.attributes('role')).toBe('alert')
  })

  it('오류가 없으면 aria-invalid 를 켜지 않는다', () => {
    const wrapper = mount(AppField, { props: { label: '아이디' } })

    expect(wrapper.find('input').attributes('aria-invalid')).toBeUndefined()
  })

  it('메시지가 없으면 aria-describedby 를 걸지 않는다', () => {
    const wrapper = mount(AppField, { props: { label: '아이디' } })

    expect(wrapper.find('input').attributes('aria-describedby')).toBeUndefined()
  })

  // FieldShell 이 라벨/에러 배치를 맡은 뒤로 생긴 회귀 — hasMessage 를 setup 시점에
  // 한 번만 계산하면, 마운트 직후엔 없다가 blur 검증으로 나중에 생기는 에러가
  // aria-describedby 에 연결되지 않는다(화면엔 보이는데 스크린리더는 못 읽음).
  it('마운트 뒤에 생기는 에러도 aria-describedby 로 연결된다', async () => {
    const wrapper = mount(AppField, { props: { label: '아이디' } })
    expect(wrapper.find('input').attributes('aria-describedby')).toBeUndefined()

    await wrapper.setProps({ error: '이미 사용 중입니다' })

    const describedBy = wrapper.find('input').attributes('aria-describedby')
    expect(describedBy).toBeTruthy()
    expect(wrapper.find(`#${describedBy}`).text()).toBe('이미 사용 중입니다')
  })

  it('success 문구도 aria-describedby 로 연결된다', () => {
    const wrapper = mount(AppField, {
      props: { label: '아이디', success: '사용 가능한 아이디입니다' }
    })

    const input = wrapper.find('input')
    const describedBy = input.attributes('aria-describedby')
    expect(describedBy).toBeTruthy()
    expect(wrapper.find(`#${describedBy}`).text()).toBe('사용 가능한 아이디입니다')
  })
})
