import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'

import { useEarningTick } from '@/composables/useEarningTick'

const EARNING = { agreedWage: 90000, checkedInAt: '2026-07-22T10:00:00' }
const WORK_CASE = { workDate: '2026-07-22', startTime: '10:00', endTime: '18:00' }

/** 컴포저블은 생명주기 훅을 쓰므로 호스트 컴포넌트 안에서 실행한다. */
function mountTick(earning = EARNING, workCase = WORK_CASE) {
  let api
  const Host = defineComponent({
    setup() {
      api = useEarningTick(ref(earning), ref(workCase))
      return () => h('div')
    }
  })
  const wrapper = mount(Host)
  return { wrapper, api }
}

describe('useEarningTick', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('마운트 즉시 현재 적립액을 계산한다', () => {
    vi.setSystemTime(new Date('2026-07-22T14:00:00'))
    const { api } = mountTick()
    expect(api.elapsedPay.value).toBe(45000)
  })

  it('1분이 지나면 적립액이 갱신된다', async () => {
    vi.setSystemTime(new Date('2026-07-22T14:00:00'))
    const { api } = mountTick()
    expect(api.elapsedPay.value).toBe(45000)

    // advanceTimersByTime 은 가짜 시계도 함께 밀어준다 — setSystemTime 을 겹쳐 부르면
    // 두 이동이 누적돼 2분이 지나버린다. 여기서는 tick 한 번 = 1분만 진행시킨다.
    // 241분 / 480분 × 90,000 = 45,187.5 → 45,187
    vi.advanceTimersByTime(60_000)
    expect(api.elapsedPay.value).toBe(45187)
  })

  it('근무가 끝난 뒤에는 타이머를 걸지 않는다', () => {
    vi.setSystemTime(new Date('2026-07-22T20:00:00'))
    const { api } = mountTick()
    expect(api.progressRatio.value).toBe(1)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('earning 이 없으면 0을 반환하고 타이머를 걸지 않는다', () => {
    vi.setSystemTime(new Date('2026-07-22T14:00:00'))
    const { api } = mountTick(null)
    expect(api.elapsedPay.value).toBe(0)
    expect(api.progressRatio.value).toBe(0)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('아직 체크인 전(checkedInAt=null)이면 지각으로 예정 시각이 지나도 0을 반환한다', () => {
    // #466 — 출근 QR 전에는 근무 경과 예상금액이 올라가면 안 된다.
    vi.setSystemTime(new Date('2026-07-22T14:00:00'))
    const { api } = mountTick({ agreedWage: 90000, checkedInAt: null })
    expect(api.elapsedPay.value).toBe(0)
    expect(api.progressRatio.value).toBe(0)
  })

  it('체크인 전엔 lateRatio가 지각분만큼 실시간으로 채워진다', () => {
    // 10:00 시작 예정, 10:48 기준 → 48/480
    vi.setSystemTime(new Date('2026-07-22T10:48:00'))
    const { api } = mountTick({ agreedWage: 90000, checkedInAt: null })
    expect(api.lateRatio.value).toBeCloseTo(48 / 480)
  })

  it('정시 체크인 후엔 lateRatio가 0이다', () => {
    vi.setSystemTime(new Date('2026-07-22T14:00:00'))
    const { api } = mountTick()
    expect(api.lateRatio.value).toBe(0)
  })

  it('지각 체크인 후엔 lateRatio가 체크인 시점 폭으로 고정되고 시간이 흘러도 바뀌지 않는다', async () => {
    // 10:00 시작 예정, 10:30 지각 체크인 → 30/480 으로 고정
    vi.setSystemTime(new Date('2026-07-22T10:31:00'))
    const { api } = mountTick({ agreedWage: 90000, checkedInAt: '2026-07-22T10:30:00' })
    expect(api.lateRatio.value).toBeCloseTo(30 / 480)

    vi.advanceTimersByTime(2 * 60 * 60_000)
    expect(api.lateRatio.value).toBeCloseTo(30 / 480)
  })

  it('언마운트하면 타이머를 해제한다', () => {
    vi.setSystemTime(new Date('2026-07-22T14:00:00'))
    const { wrapper } = mountTick()
    expect(vi.getTimerCount()).toBe(1)

    wrapper.unmount()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('탭으로 돌아오면 즉시 다시 계산한다', () => {
    vi.setSystemTime(new Date('2026-07-22T14:00:00'))
    const { api } = mountTick()
    expect(api.elapsedPay.value).toBe(45000)

    // 백그라운드에서 타이머가 throttle 된 상황을 흉내낸다 — 시간만 흐르고 tick 은 없다.
    // `Event`/`KeyboardEvent` 는 eslint.config.js 전역 화이트리스트에 없다. 공유 설정을
    // 건드리지 않도록 이미 등재된 `window` 를 거쳐 생성한다.
    vi.setSystemTime(new Date('2026-07-22T16:00:00'))
    document.dispatchEvent(new window.Event('visibilitychange'))
    expect(api.elapsedPay.value).toBe(67500)
  })
})
