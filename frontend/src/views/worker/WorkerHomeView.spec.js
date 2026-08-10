import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import WorkerHomeView from '@/views/worker/WorkerHomeView.vue'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

vi.mock('@/services/worker', () => ({ getWorkerHome: vi.fn() }))
vi.mock('@/services/wallet', () => ({ fetchWallet: vi.fn(), fetchTransactions: vi.fn() }))

import { fetchWallet } from '@/services/wallet'
import { getWorkerHome } from '@/services/worker'

const homePayload = {
  todayWorkCase: {
    status: 'LATE',
    title: '주말 홀 서빙',
    workplaceName: '카페 봄',
    workDate: '2026-07-22',
    startTime: '10:00',
    endTime: '18:00'
  },
  earning: {
    agreedWage: 90000,
    totalMinutes: 480,
    unpaidBreakMinutes: 60,
    elapsedPayDisplay: 34526,
    progressRatio: 0.42,
    expectedNetAmount: 90000,
    isLate: true,
    lateMinutes: 15
  }
}

describe('WorkerHomeView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    push.mockClear()
    getWorkerHome.mockResolvedValue(structuredClone(homePayload))
    fetchWallet
      .mockReset()
      .mockResolvedValue({ currency: 'KRW', availableBalance: 320_000, lockedBalance: 0 })
  })

  it('안심지갑 잔액·오늘의 알바·확보 안심금액을 표시한다', async () => {
    const wrapper = mount(WorkerHomeView)
    await flushPromises()

    expect(wrapper.text()).toContain('320,000원') // 안심지갑 잔액(공용 wallet Store)
    expect(wrapper.text()).toContain('주말 홀 서빙') // 오늘의 알바
    expect(wrapper.text()).toContain('현재까지 확보한 안심금액') // 안심금액 카드
    expect(wrapper.text()).toContain('일급 90,000원') // agreedWage 로 읽는지 확인
  })

  it('오늘 근무가 없으면 안심금액 카드를 숨긴다', async () => {
    getWorkerHome.mockResolvedValue({
      todayWorkCase: { status: 'NONE' },
      earning: null
    })
    const wrapper = mount(WorkerHomeView)
    await flushPromises()

    expect(wrapper.text()).not.toContain('현재까지 확보한 안심금액')
    expect(wrapper.text()).toContain('오늘은 예정된 알바가 없어요.')
  })

  it('출금 버튼은 출금 화면으로 이동한다', async () => {
    const wrapper = mount(WorkerHomeView)
    await flushPromises()

    await wrapper.get('.wallet-card button').trigger('click')
    expect(push).toHaveBeenCalledWith('/worker/wallet/withdraw')
  })

  it('GET /wallet 실패는 미처리 예외 없이 오류 화면으로 표시한다', async () => {
    fetchWallet.mockReset().mockRejectedValue(new Error('network'))
    const unhandled = vi.fn()
    process.on('unhandledRejection', unhandled)

    const wrapper = mount(WorkerHomeView)
    await flushPromises()

    expect(wrapper.find('.wallet-card').exists()).toBe(false)
    expect(wrapper.text()).toContain('홈 정보를 불러오지 못했습니다.')
    expect(unhandled).not.toHaveBeenCalled()

    process.off('unhandledRejection', unhandled)
  })

  it('미구현 worker home은 fake content 대신 준비 중 상태를 표시한다', async () => {
    getWorkerHome.mockRejectedValueOnce({ code: 'FEATURE_UNAVAILABLE' })
    const wrapper = mount(WorkerHomeView)
    await flushPromises()

    expect(wrapper.text()).toContain('알바생 홈은 현재 준비 중인 기능입니다.')
    expect(wrapper.text()).not.toContain('주말 홀 서빙')
  })
})
