/**
 * '오늘의 알바' 카드의 근무 시간 표기(SPEC-413-01).
 *
 * 카드가 startTime·endTime 을 직접 이어 붙이면 자정을 넘긴 근무가 '23:00–01:00' 이 되어
 * 2시간 근무와 22시간 근무를 구분할 수 없다. 표기 규칙은 formatSeoulTimeRange 한 곳에만
 * 두기로 했으므로, 카드는 그 결과를 실어 오는 timeRange 만 읽어야 한다.
 */
import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import TodayWorkCaseCard from '@/components/worker/TodayWorkCaseCard.vue'

const WORK_CASE = {
  workCaseId: 101,
  title: '야간 마감',
  workplaceName: '카페 봄',
  status: 'READY',
  startTime: '23:00',
  endTime: '01:00',
  timeRange: '23:00 ~ 익일 01:00',
  attendance: { isLate: false, lateMinutes: 0 }
}

const mountCard = (workCase = WORK_CASE) => mount(TodayWorkCaseCard, { props: { workCase } })

describe('TodayWorkCaseCard', () => {
  it('근무 시간대를 timeRange 그대로 보여준다', () => {
    const info = mountCard().get('.work-case-info').text()

    expect(info).toContain('23:00 ~ 익일 01:00')
    // startTime–endTime 을 직접 이어 붙이던 표기로 되돌아가면 여기서 걸린다.
    expect(info).not.toContain('23:00–01:00')
  })

  it('근무가 없으면 안내 문구만 남는다', () => {
    const wrapper = mountCard(null)

    expect(wrapper.text()).toContain('오늘은 예정된 알바가 없어요.')
    expect(wrapper.find('.work-case-info').exists()).toBe(false)
  })

  describe('체크인 전 지각 표시', () => {
    beforeEach(() => {
      // 시작 시각(01:00Z)이 지난 시점으로 시계를 고정한다.
      vi.useFakeTimers()
      vi.setSystemTime(new Date('2026-08-20T01:30:00Z'))
    })

    afterEach(() => {
      vi.useRealTimers()
    })

    it('READY이고 체크인 전인데 시작 시각이 지났으면 상태 칩이 지각으로 바뀐다', () => {
      const wrapper = mountCard({
        ...WORK_CASE,
        status: 'READY',
        startsAt: '2026-08-20T01:00:00Z',
        attendance: { checkedInAt: null, isLate: false, lateMinutes: 0 }
      })

      expect(wrapper.text()).toContain('지각')
      expect(wrapper.text()).not.toContain('근무예정')
    })

    it('체크인 후에는 시작 시각이 지났어도 상태 칩을 지각으로 바꾸지 않는다', () => {
      const wrapper = mountCard({
        ...WORK_CASE,
        status: 'IN_PROGRESS',
        startsAt: '2026-08-20T01:00:00Z',
        attendance: { checkedInAt: '2026-08-20T01:10:00Z', isLate: true, lateMinutes: 10 }
      })

      expect(wrapper.text()).toContain('근무중')
      expect(wrapper.text()).toContain('지각 10분') // attendance.isLate 배지는 그대로 유지
    })
  })
})
