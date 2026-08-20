import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import TransactionItem from '@/components/wallet/TransactionItem.vue'

const baseTransaction = {
  transactionId: 1,
  type: 'ESCROW_RELEASE',
  amount: 120_000,
  direction: 'CREDIT',
  availableAfter: 120_000,
  lockedAfter: 0,
  workCaseId: 10,
  workTitle: '주말 홀 서빙',
  workplaceName: '기가 허브',
  displayStatus: 'COMPLETED',
  createdAt: '2026-08-06T00:00:00Z'
}

describe('TransactionItem', () => {
  it('WORKER ESCROW_RELEASE는 direction=CREDIT에 따라 양수로 표시한다', () => {
    const wrapper = mount(TransactionItem, { props: { tx: baseTransaction } })

    expect(wrapper.get('.amount').text()).toBe('+120,000원')
    expect(wrapper.get('.amount').classes()).toContain('is-credit')
    expect(wrapper.get('.status').text()).toContain('완료')
    expect(wrapper.get('.desc').text()).toBe('정산 지급 · 주말 홀 서빙 · 기가 허브')
  })

  it('같은 Type이어도 direction=DEBIT이면 음수로 표시한다', () => {
    const wrapper = mount(TransactionItem, {
      props: { tx: { ...baseTransaction, direction: 'DEBIT' } }
    })

    expect(wrapper.get('.amount').text()).toBe('-120,000원')
    expect(wrapper.get('.amount').classes()).not.toContain('is-credit')
    expect(wrapper.get('.desc').text()).toBe('알바생 지급 · 주말 홀 서빙 · 기가 허브')
  })

  it('차감액 반환 거래는 근무 제목과 함께 예치 환불 유형을 표시한다', () => {
    const wrapper = mount(TransactionItem, {
      props: { tx: { ...baseTransaction, type: 'ESCROW_REFUND', direction: 'CREDIT' } }
    })

    expect(wrapper.get('.desc').text()).toBe('예치 환불 · 주말 홀 서빙 · 기가 허브')
  })
})
