import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'

import SecuredEarningCard from '@/components/worker/SecuredEarningCard.vue'

const WORK_CASE = { workDate: '2026-07-22', startTime: '10:00', endTime: '18:00' }

// expectedNetAmount: null — 서버가 아직 안 준 상태를 기본으로 두어, 기본 픽스처를 쓰는 테스트는
// calcDailyTax 폴백 경로를 검증한다. 서버 값 우선 규칙은 별도 테스트에서 확인한다.
const earningOf = (agreedWage) => ({
  agreedWage,
  totalMinutes: 480,
  unpaidBreakMinutes: 60,
  elapsedPayDisplay: 0,
  progressRatio: 0,
  expectedNetAmount: null,
  isLate: false,
  lateMinutes: 0
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

  it('시계가 흘러 참고 예상금액이 갱신돼도 서버가 준 실제 금액 필드는 바뀌지 않는다', async () => {
    // earning props는 서버 기준값(agreedWage/expectedNetAmount)이다 — 1분 tick 은 표시용
    // elapsedPay 만 다시 계산할 뿐, 여기 값을 계산해 넣거나 되돌려쓰지 않는다.
    const earning = earningOf(90000)
    const wrapper = mount(SecuredEarningCard, { props: { earning, workCase: WORK_CASE } })
    await nextTick()
    expect(wrapper.text()).toContain('45,000원')

    vi.advanceTimersByTime(60_000)
    await nextTick()

    expect(wrapper.text()).toContain('45,187원') // elapsedPay 표시값은 갱신된다
    expect(earning.agreedWage).toBe(90000) // 서버가 준 실제 금액 필드는 불변
    expect(earning.expectedNetAmount).toBeNull()
    expect(wrapper.text()).toContain('예상 실수령액 90,000원') // 실수령액도 시간과 무관하게 고정
  })

  it('세금이 없으면 실수령액에 공제 없음을 표시한다', () => {
    const wrapper = mountCard(90000)
    expect(wrapper.text()).toContain('예상 실수령액')
    expect(wrapper.text()).toContain('90,000원')
    expect(wrapper.text()).toContain('세금 공제 없음')
  })

  it('세금이 있으면 실수령액과 공제액을 함께 표시한다', () => {
    const wrapper = mountCard(200000)
    expect(wrapper.text()).toContain('198,520원')
    expect(wrapper.text()).toContain('세금 1,480원 공제')
  })

  it('서버가 준 expectedNetAmount 가 있으면 자체 계산값 대신 그 값을 쓴다', () => {
    // calcDailyTax(200000) 은 198,520원을 내지만, 서버 값 198,000원이 우선해야 한다.
    const earning = { ...earningOf(200000), expectedNetAmount: 198000 }
    const wrapper = mount(SecuredEarningCard, { props: { earning, workCase: WORK_CASE } })
    expect(wrapper.text()).toContain('198,000원')
    expect(wrapper.text()).toContain('세금 2,000원 공제')
    expect(wrapper.text()).not.toContain('198,520원')
  })

  it('i 아이콘을 누르면 안내가 열리고 다시 누르면 닫힌다', async () => {
    const wrapper = mountCard(90000, { attachTo: document.body })
    expect(wrapper.find('.info-popover').exists()).toBe(false)

    await wrapper.find('button.info').trigger('click')
    expect(wrapper.find('.info-popover').exists()).toBe(true)
    expect(wrapper.text()).toContain('1분마다 갱신되는 참고용 예상치')
    expect(wrapper.text()).toContain('지갑 잔액·예치금·실제 지급액과는 무관') // 비금융 참고값 명시
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
