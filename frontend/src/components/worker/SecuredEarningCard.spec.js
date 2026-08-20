import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'

import SecuredEarningCard from '@/components/worker/SecuredEarningCard.vue'

const WORK_CASE = { workDate: '2026-07-22', startTime: '10:00', endTime: '18:00' }

const earningOf = (agreedWage) => ({
  agreedWage,
  totalMinutes: 480,
  unpaidBreakMinutes: 60,
  elapsedPayDisplay: 0,
  progressRatio: 0,
  isLate: false,
  lateMinutes: 0,
  checkedInAt: '2026-07-22T10:00:00'
})

const mountCard = (agreedWage = 90000, options = {}) =>
  mount(SecuredEarningCard, {
    props: { earning: earningOf(agreedWage), workCase: WORK_CASE },
    ...options
  })

describe('SecuredEarningCard', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-22T14:00:00'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('제목과 프론트 계산 참고 예상금액을 보여준다', async () => {
    const wrapper = mountCard()
    // useEarningTick 은 onMounted 에서 값을 채운다 — 초기 렌더 이후이므로
    // 반영을 기다려야 한다. 실제 화면에서는 Vue 가 paint 전에 flush 하므로
    // 0원이 보이는 순간은 없다.
    await nextTick()
    expect(wrapper.text()).toContain('근무 경과 예상금액')
    expect(wrapper.text()).toContain('45,000원') // 10:00~18:00 중 14:00 → 절반
  })

  it('시계가 흘러 참고 예상금액이 갱신돼도 서버가 준 약정 일급은 바뀌지 않는다', async () => {
    const earning = earningOf(90000)
    const wrapper = mount(SecuredEarningCard, { props: { earning, workCase: WORK_CASE } })
    await nextTick()
    expect(wrapper.text()).toContain('45,000원')

    vi.advanceTimersByTime(60_000)
    await nextTick()

    expect(wrapper.text()).toContain('45,187원') // elapsedPay 표시값은 갱신된다
    expect(earning.agreedWage).toBe(90000) // 서버가 준 실제 금액 필드는 불변
    expect(wrapper.text()).not.toContain('예상 실수령액')
    expect(wrapper.text()).not.toContain('세금 공제')
  })

  it('아직 체크인 전이면(checkedInAt=null) 지각으로 시각이 지나도 0원을 보여준다', async () => {
    // #466 — 출근 QR 전에는 근무 경과 예상금액이 올라가면 안 된다.
    const wrapper = mount(SecuredEarningCard, {
      props: { earning: { ...earningOf(90000), checkedInAt: null }, workCase: WORK_CASE }
    })
    await nextTick()
    expect(wrapper.text()).toContain('0원')
  })

  it('체크인 전엔 지각분만큼 주황 막대가 채워진다', async () => {
    // 10:00 시작 예정, 10:48 기준(48분 지각) → 48/480 = 10%
    vi.setSystemTime(new Date('2026-07-22T10:48:00'))
    const wrapper = mount(SecuredEarningCard, {
      props: { earning: { ...earningOf(90000), checkedInAt: null }, workCase: WORK_CASE }
    })
    await nextTick()

    const seg = wrapper.get('.seg')
    expect(seg.classes()).toContain('seg-late')
    expect(seg.attributes('style')).toContain('width: 10%')
  })

  it('체크인 후엔 지각 막대가 아니라 근무 경과 막대(노랑)를 보여준다', async () => {
    const wrapper = mountCard() // checkedInAt 있음, 14:00 기준 절반 경과
    await nextTick()

    const seg = wrapper.get('.seg')
    expect(seg.classes()).not.toContain('seg-late')
    expect(seg.attributes('style')).toContain('width: 50%')
  })

  it('i 아이콘을 누르면 안내가 열리고 다시 누르면 닫힌다', async () => {
    const wrapper = mountCard(90000, { attachTo: document.body })
    expect(wrapper.find('.info-popover').exists()).toBe(false)

    await wrapper.find('button.info').trigger('click')
    expect(wrapper.find('.info-popover').exists()).toBe(true)
    expect(wrapper.text()).toContain('1분마다 갱신되는 참고용 예상치')
    expect(wrapper.text()).toContain('지갑 잔액·예치금·실제 지급액과는 무관')
    expect(wrapper.find('button.info').attributes('aria-expanded')).toBe('true')

    await wrapper.find('button.info').trigger('click')
    expect(wrapper.find('.info-popover').exists()).toBe(false)

    wrapper.unmount()
  })

  it('바깥을 클릭하면 안내가 닫힌다', async () => {
    const wrapper = mountCard(90000, { attachTo: document.body })

    await wrapper.find('button.info').trigger('click')
    expect(wrapper.find('.info-popover').exists()).toBe(true)

    document.body.click()
    await nextTick()
    expect(wrapper.find('.info-popover').exists()).toBe(false)

    wrapper.unmount()
  })

  it('Escape 로도 안내가 닫힌다', async () => {
    const wrapper = mountCard(90000, { attachTo: document.body })

    await wrapper.find('button.info').trigger('click')
    expect(wrapper.find('.info-popover').exists()).toBe(true)

    // `KeyboardEvent` 는 eslint 전역 화이트리스트에 없다 — 등재된 `window` 를 거친다.
    document.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape' }))
    await nextTick()
    expect(wrapper.find('.info-popover').exists()).toBe(false)

    wrapper.unmount()
  })
})
