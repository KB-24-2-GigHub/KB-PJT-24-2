/**
 * '오늘의 알바' 카드의 근무 시간 표기(SPEC-413-01).
 *
 * 카드가 startTime·endTime 을 직접 이어 붙이면 자정을 넘긴 근무가 '23:00–01:00' 이 되어
 * 2시간 근무와 22시간 근무를 구분할 수 없다. 표기 규칙은 formatSeoulTimeRange 한 곳에만
 * 두기로 했으므로, 카드는 그 결과를 실어 오는 timeRange 만 읽어야 한다.
 */
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

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
})
