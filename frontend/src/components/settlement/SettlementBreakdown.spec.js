import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import SettlementBreakdown from '@/components/settlement/SettlementBreakdown.vue'

const SNAPSHOT = {
  status: 'SCHEDULED',
  amount: 100000,
  workerPaidAmount: 85710,
  ownerRefundAmount: 14290,
  deductionAmount: 14290,
  deductionBaseMinutes: 210,
  lateMinutes: 30,
  earlyLeaveMinutes: 0,
  calculationReason: 'CHECKED_OUT',
  calculationVersion: 'ATTENDANCE_V1',
  calculatedAt: '2026-08-20T03:00:00Z'
}

describe('SettlementBreakdown', () => {
  it('서버 Snapshot의 약정액·근태·차감·지급·환불을 그대로 표시한다', () => {
    const wrapper = mount(SettlementBreakdown, { props: { settlement: SNAPSHOT } })

    const text = wrapper.text()
    expect(text).toContain('약정 일급100,000원')
    expect(text).toContain('차감 기준 시간210분')
    expect(text).toContain('지각30분')
    expect(text).toContain('조퇴0분')
    expect(text).toContain('근태 차감액14,290원')
    expect(text).toContain('알바생 지급액85,710원')
    expect(text).toContain('사장님 환불액14,290원')
    expect(text).toContain('출퇴근 기록 기준')
    expect(text).toContain('2026.08.20 12:00 산정')
  })

  it('Snapshot 전에는 약정액으로 지급액을 추정하지 않는다', () => {
    const wrapper = mount(SettlementBreakdown, {
      props: {
        settlement: {
          status: 'WAITING',
          amount: 100000,
          workerPaidAmount: null,
          ownerRefundAmount: null,
          calculatedAt: null
        }
      }
    })

    expect(wrapper.text()).toContain('근무 종료 결과가 확정되면')
    expect(wrapper.text()).not.toContain('알바생 지급액')
    expect(wrapper.text()).not.toContain('사장님 환불액')
  })

  it('LEGACY Snapshot은 없는 근태 계산 필드를 만들지 않고 실제 결과만 표시한다', () => {
    const wrapper = mount(SettlementBreakdown, {
      props: {
        settlement: {
          ...SNAPSHOT,
          deductionBaseMinutes: null,
          lateMinutes: null,
          earlyLeaveMinutes: null,
          calculationReason: 'LEGACY'
        }
      }
    })

    expect(wrapper.text()).toContain('기존 정산 기록')
    expect(wrapper.text()).not.toContain('차감 기준 시간')
    expect(wrapper.text()).not.toContain('지각')
    expect(wrapper.text()).not.toContain('조퇴')
  })
})
