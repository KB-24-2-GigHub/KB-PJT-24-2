/**
 * AppTimeField 표시값·저장값 일치 계약 테스트.
 *
 * 이 다이얼로는 10분 단위가 아닌 값을 만들 수 없지만, modelValue 는 이 다이얼이
 * 아니라 DB(과거 데이터·다른 경로로 등록된 값)에서 올 수도 있다. "화면에 보이는
 * 값"과 "실제 emit 되어 저장되는 값"이 항상 같아야 한다 — PR #455 리뷰에서 09:23
 * 같은 값이 닫힌 필드/다이얼엔 09:20 으로 보이지만 확인을 누르면 09:23 이 그대로
 * 저장되던 불일치가 지적됐다.
 */
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import AppTimeField from '@/components/common/AppTimeField.vue'

function mountField(modelValue) {
  return mount(AppTimeField, {
    props: { label: '시작시간', modelValue },
    global: { stubs: { teleport: true } }
  })
}

describe('AppTimeField 표시값/저장값 일치', () => {
  it('10분 단위가 아닌 값을 받으면 마운트 즉시 반올림해서 emit 한다', () => {
    const wrapper = mountField('09:23')

    expect(wrapper.emitted('update:modelValue')).toEqual([['09:20']])
  })

  it('시(hour) 자리올림이 필요한 값도 마운트 즉시 정확히 반올림한다', () => {
    const wrapper = mountField('14:55')

    expect(wrapper.emitted('update:modelValue')).toEqual([['15:00']])
  })

  it('이미 10분 단위인 값은 emit 하지 않는다', () => {
    const wrapper = mountField('09:20')

    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('닫힌 필드에 보이는 표시값과, 시트를 열고 아무것도 건드리지 않고 확인했을 때 emit 되는 값이 같다', async () => {
    const wrapper = mountField('09:23')

    // 마운트 시 자동 반올림된 값(09:20)이 부모 v-model 이라고 가정하고 다시 흘려준다 —
    // 실사용에서는 form.startTime 이 이 emit 을 받아 갱신된다.
    await wrapper.setProps({ modelValue: '09:20' })
    expect(wrapper.get('button.time-input').text()).toBe('오전 09:20')

    await wrapper.get('button.time-input').trigger('click')
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '확인')
      .trigger('click')

    const emissions = wrapper.emitted('update:modelValue')
    expect(emissions.at(-1)).toEqual(['09:20'])
  })

  // PR #455 리뷰: WheelColumn 이 스크롤 정착(120ms) 뒤에야 commit 하면, 화면엔 이미
  // 새 값이 보이는데 정착 전에 확인을 눌러 emit 은 아직 이전 값인 채로 나갈 수 있었다.
  // 정착을 기다리지 않고(=이 테스트는 120ms 를 기다리지 않는다) 확인을 눌러도 최신
  // 스크롤 위치의 값이 저장돼야 한다.
  it('스크롤 정착 전에 확인을 눌러도 이미 스크롤된 값이 저장된다', async () => {
    const wrapper = mountField('09:20')
    await wrapper.get('button.time-input').trigger('click')
    // 시트가 열리며 각 휠이 초기 위치로 스크롤한다(programmatic) — 그 정리가 끝나길
    // 기다린 뒤에야 "사용자가 스크롤한" 상황을 재현할 수 있다.
    await new Promise((resolve) => setTimeout(resolve, 0))

    const minuteCol = wrapper.findAll('.wheel-col')[2] // 오전/오후, 시, 분
    // 09:20(분 옵션 index 2) → index 5(=50분)로 스크롤한다.
    minuteCol.element.scrollTop = 5 * 44
    await minuteCol.trigger('scroll')
    // commit 은 settle 타이머(120ms)가 아니라 rAF 로 즉시 일어난다 — 한 프레임만 기다린다.
    await new Promise((resolve) => requestAnimationFrame(resolve))
    await wrapper.vm.$nextTick()

    // 120ms 를 기다리지 않고 곧바로 확인을 누른다.
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '확인')
      .trigger('click')

    const emissions = wrapper.emitted('update:modelValue')
    expect(emissions.at(-1)).toEqual(['09:50'])
  })
})
