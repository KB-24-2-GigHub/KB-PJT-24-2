import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import TimePickerField from '@/components/common/TimePickerField.vue'

function mountField(props = {}) {
  return mount(TimePickerField, {
    props: { label: '시작시간', ...props },
    global: { stubs: { teleport: true } }
  })
}

const trigger = (wrapper) => wrapper.find('button.time-trigger')
const chipByLabel = (wrapper, selector, label) =>
  wrapper.findAll(selector).find((node) => node.text() === label)

async function openSheet(wrapper) {
  await trigger(wrapper).trigger('click')
  return wrapper
}

/** 시트를 열어 "오전|오후 · 시 · 분" 을 차례로 고르고 확인까지 누른다. */
async function pick(wrapper, { meridiem, hour, minute }) {
  await openSheet(wrapper)
  if (meridiem) await chipByLabel(wrapper, '.meridiem-btn', meridiem).trigger('click')
  if (hour) await chipByLabel(wrapper, '.hour-btn', hour).trigger('click')
  if (minute) await chipByLabel(wrapper, '.minute-btn', minute).trigger('click')
  await wrapper.find('.picker-confirm').trigger('click')
}

const lastModelValue = (wrapper) => {
  const emitted = wrapper.emitted('update:modelValue')
  return emitted ? emitted.at(-1)[0] : undefined
}

/**
 * 값 계약 — 화면은 12시간제로 보여주고 모델은 "HH:mm" 24시간제로 유지한다.
 * 이 경계가 무너지면 서버 payload 와 workPeriodRule 이 동시에 깨진다.
 */
describe('TimePickerField 값 계약', () => {
  it('분 선택지는 정확히 00·10·20·30·40·50 여섯 개다', async () => {
    // 금지 목록이 아니라 승인 집합으로 고정한다 — "05 가 없다"류 단언은 새로 생긴
    // 어긋난 선택지를 못 잡는다.
    const wrapper = await openSheet(mountField())

    const minutes = wrapper.findAll('.minute-btn').map((node) => node.text())

    expect(minutes).toEqual(['00', '10', '20', '30', '40', '50'])
  })

  it('시 선택지는 12시간제 12개다', async () => {
    const wrapper = await openSheet(mountField())

    const hours = wrapper.findAll('.hour-btn').map((node) => node.text())

    expect(hours).toEqual(['12', '1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11'])
  })

  it('오후 5시 00분을 고르면 24시간 표기로 올린다', async () => {
    const wrapper = mountField()

    await pick(wrapper, { meridiem: '오후', hour: '5', minute: '00' })

    expect(lastModelValue(wrapper)).toBe('17:00')
  })

  it('오전 12시는 자정(00:00)으로 올린다', async () => {
    const wrapper = mountField()

    await pick(wrapper, { meridiem: '오전', hour: '12', minute: '30' })

    expect(lastModelValue(wrapper)).toBe('00:30')
  })

  it('오후 12시는 정오(12:00)로 올린다', async () => {
    const wrapper = mountField()

    await pick(wrapper, { meridiem: '오후', hour: '12', minute: '00' })

    expect(lastModelValue(wrapper)).toBe('12:00')
  })

  it('확인을 누르기 전에는 값을 올리지 않는다', async () => {
    const wrapper = mountField({ modelValue: '09:00' })

    await openSheet(wrapper)
    await chipByLabel(wrapper, '.meridiem-btn', '오후').trigger('click')
    await chipByLabel(wrapper, '.hour-btn', '11').trigger('click')

    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('자정과 정오를 12시간 표기로 구분해 보여준다', () => {
    expect(trigger(mountField({ modelValue: '00:00' })).text()).toBe('오전 12:00')
    expect(trigger(mountField({ modelValue: '12:00' })).text()).toBe('오후 12:00')
    expect(trigger(mountField({ modelValue: '17:00' })).text()).toBe('오후 5:00')
    expect(trigger(mountField({ modelValue: '09:05' })).text()).toBe('오전 9:05')
  })

  it('값이 없으면 안내 문구를 보여준다', () => {
    expect(trigger(mountField()).text()).toBe('시간 선택')
  })

  /**
   * 10분 단위는 이 화면에서 새로 만드는 값에만 적용된다. 서버에 이미 09:25 로 저장된
   * 근무를 수정 화면에서 열었을 때 조용히 09:20 으로 반올림하면, 사용자가 건드리지도
   * 않은 근무 시각이 바뀐 채 저장된다.
   */
  it('10분 배수가 아닌 기존 값은 표시도 모델도 바꾸지 않는다', async () => {
    const wrapper = mountField({ modelValue: '09:25' })

    expect(trigger(wrapper).text()).toBe('오전 9:25')

    await openSheet(wrapper)

    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('10분 배수가 아닌 값을 열면 가장 가까운 아래 눈금에 커서를 둔다', async () => {
    const wrapper = await openSheet(mountField({ modelValue: '09:25' }))

    expect(wrapper.find('.meridiem-btn[aria-checked="true"]').text()).toBe('오전')
    expect(wrapper.find('.hour-btn[aria-checked="true"]').text()).toBe('9')
    expect(wrapper.find('.minute-btn[aria-checked="true"]').text()).toBe('20')
  })
})

/**
 * 길이 상한(SPEC-413-01) — 자정 넘김을 허용한 뒤로 "종료가 시작보다 이르다"는 방어선이
 * 사라졌고 16시간 상한이 그 자리를 대신한다. 에러로 알리는 대신 고를 수 없게 만든다.
 */
describe('TimePickerField 길이 상한', () => {
  const maxFrom = { time: '18:00', minutes: 16 * 60 }

  async function openEndPicker(props = {}) {
    return openSheet(mountField({ maxFrom, ...props }))
  }

  /**
   * 시 칩을 누른 뒤 그 시가 실제로 선택됐는지 함께 본다. 상한이 틀어지면 시 칩부터
   * 비활성이 되어 클릭이 먹지 않고, 그러면 분 칩 단언은 엉뚱한 시(직전 커서)의 값을
   * 보게 된다. 그 상태로는 상한을 깨도 테스트가 통과한다.
   */
  async function selectHour(wrapper, meridiem, hour) {
    await chipByLabel(wrapper, '.meridiem-btn', meridiem).trigger('click')
    await chipByLabel(wrapper, '.hour-btn', hour).trigger('click')
    expect(wrapper.find('.meridiem-btn[aria-checked="true"]').text()).toBe(meridiem)
    expect(wrapper.find('.hour-btn[aria-checked="true"]').text()).toBe(hour)
  }

  it('정확히 16시간이 되는 시각은 고를 수 있다', async () => {
    const wrapper = await openEndPicker()

    await selectHour(wrapper, '오전', '10')
    expect(chipByLabel(wrapper, '.minute-btn', '00').attributes('disabled')).toBeUndefined()

    await chipByLabel(wrapper, '.minute-btn', '00').trigger('click')
    await wrapper.find('.picker-confirm').trigger('click')

    expect(lastModelValue(wrapper)).toBe('10:00')
  })

  it('16시간을 1분이라도 넘기는 시각은 고를 수 없다', async () => {
    const wrapper = await openEndPicker()

    await selectHour(wrapper, '오전', '10')

    expect(chipByLabel(wrapper, '.minute-btn', '10').attributes('disabled')).toBeDefined()
  })

  it('시작과 같은 시각은 0분이 아니라 24시간이라 고를 수 없다', async () => {
    const wrapper = await openEndPicker()

    await selectHour(wrapper, '오후', '6')

    expect(chipByLabel(wrapper, '.minute-btn', '00').attributes('disabled')).toBeDefined()
    expect(chipByLabel(wrapper, '.minute-btn', '10').attributes('disabled')).toBeUndefined()
  })

  it('고를 수 있는 분이 하나도 없는 시는 시 칩부터 막는다', async () => {
    const wrapper = await openEndPicker()

    await chipByLabel(wrapper, '.meridiem-btn', '오전').trigger('click')

    // 오전 11시대는 시작 18:00 기준 17시간 이상이라 여섯 눈금 모두 상한 밖이다.
    expect(chipByLabel(wrapper, '.hour-btn', '11').attributes('disabled')).toBeDefined()
    expect(chipByLabel(wrapper, '.hour-btn', '10').attributes('disabled')).toBeUndefined()
  })

  it('시작시간이 아직 비어 있으면 아무 시각도 막지 않는다', async () => {
    const wrapper = await openEndPicker({ maxFrom: { time: '', minutes: 16 * 60 } })

    const disabled = wrapper.findAll('.hour-btn, .minute-btn').filter((node) => {
      return node.attributes('disabled') !== undefined
    })

    expect(disabled).toHaveLength(0)
  })

  it('maxFrom 없이 쓰면 아무 시각도 막지 않는다', async () => {
    const wrapper = await openSheet(mountField())

    const disabled = wrapper.findAll('.hour-btn, .minute-btn').filter((node) => {
      return node.attributes('disabled') !== undefined
    })

    expect(disabled).toHaveLength(0)
  })
})
