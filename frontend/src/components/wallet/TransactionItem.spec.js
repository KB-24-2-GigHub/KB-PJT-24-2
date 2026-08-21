import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import TransactionItem from '@/components/wallet/TransactionItem.vue'
import { formatDateTime } from '@/utils/format'

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
  it('WORKER ESCROW_RELEASE는 direction=CREDIT에 따라 양수로 표시하고 배지는 "지급"·owner 톤이다', () => {
    const wrapper = mount(TransactionItem, { props: { tx: baseTransaction } })

    expect(wrapper.get('.amount').text()).toBe('+120,000원')
    expect(wrapper.get('.amount').classes()).toContain('is-credit')
    expect(wrapper.get('.status').text()).toContain('완료')
    expect(wrapper.get('.type-badge').text()).toBe('지급')
    expect(wrapper.get('.type-badge').classes()).toContain('type-badge--owner')
    expect(wrapper.get('.desc').text()).toBe('주말 홀 서빙')
    expect(wrapper.get('.date').text()).toBe(
      `${formatDateTime(baseTransaction.createdAt)} | 기가 허브`
    )
  })

  it('같은 Type이어도 direction=DEBIT이면 음수로 표시하고 배지 라벨은 같지만(지급) 톤은 다르다', () => {
    const wrapper = mount(TransactionItem, {
      props: { tx: { ...baseTransaction, direction: 'DEBIT' } }
    })

    expect(wrapper.get('.amount').text()).toBe('-120,000원')
    expect(wrapper.get('.amount').classes()).not.toContain('is-credit')
    expect(wrapper.get('.type-badge').text()).toBe('지급')
    expect(wrapper.get('.type-badge').classes()).toContain('type-badge--worker')
    expect(wrapper.get('.desc').text()).toBe('주말 홀 서빙')
  })

  it('차감액 반환 거래는 근무 제목과 별도로 예치 환불 유형을 배지에 표시한다', () => {
    const wrapper = mount(TransactionItem, {
      props: { tx: { ...baseTransaction, type: 'ESCROW_REFUND', direction: 'CREDIT' } }
    })

    expect(wrapper.get('.type-badge').text()).toBe('예치 환불')
    expect(wrapper.get('.type-badge').classes()).toContain('type-badge--neutral')
    expect(wrapper.get('.desc').text()).toBe('주말 홀 서빙')
  })

  it('근무와 무관한 충전 거래는 workTitle이 없어도 2행에 "안심지갑 충전"을 표시하고 1행은 시각만 남는다', () => {
    const wrapper = mount(TransactionItem, {
      props: {
        tx: {
          ...baseTransaction,
          type: 'FUNDING',
          direction: 'CREDIT',
          workTitle: null,
          workplaceName: null
        }
      }
    })

    expect(wrapper.get('.type-badge').text()).toBe('충전')
    expect(wrapper.get('.type-badge').classes()).toContain('type-badge--success')
    expect(wrapper.get('.desc').text()).toBe('안심지갑 충전')
    expect(wrapper.get('.date').text()).toBe(formatDateTime(baseTransaction.createdAt))
  })

  it('근무와 무관한 출금 거래는 workTitle이 없어도 2행에 "안심지갑 출금"을 표시한다', () => {
    const wrapper = mount(TransactionItem, {
      props: {
        tx: {
          ...baseTransaction,
          type: 'WITHDRAWAL',
          direction: 'DEBIT',
          workTitle: null,
          workplaceName: null
        }
      }
    })

    expect(wrapper.get('.desc').text()).toBe('안심지갑 출금')
  })

  it('1행은 거래 시각·사업장명, 2행은 배지·근무제목, 3행은 상태·금액 순으로 배치된다', () => {
    const wrapper = mount(TransactionItem, { props: { tx: baseTransaction } })
    const rows = wrapper.get('.tx').element.children

    expect(rows[0].classList.contains('date')).toBe(true)
    expect(rows[1].classList.contains('row-type')).toBe(true)
    expect(rows[2].classList.contains('row-amount')).toBe(true)
  })

  it('예치는 알바생 지급(worker)과 혼동되지 않게 neutral, 출금은 danger 톤 배지로 표시한다', () => {
    const holdWrapper = mount(TransactionItem, {
      props: { tx: { ...baseTransaction, type: 'ESCROW_HOLD', direction: 'DEBIT' } }
    })
    const withdrawalWrapper = mount(TransactionItem, {
      props: { tx: { ...baseTransaction, type: 'WITHDRAWAL', direction: 'DEBIT' } }
    })

    expect(holdWrapper.get('.type-badge').classes()).toContain('type-badge--neutral')
    expect(withdrawalWrapper.get('.type-badge').classes()).toContain('type-badge--danger')
  })
})
