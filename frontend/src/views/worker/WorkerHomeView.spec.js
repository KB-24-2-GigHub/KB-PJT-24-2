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

// 실제 GET /worker/home 계약(WorkerHomeResponse) 그대로 — 최상위 earning 필드는 없다.
const homePayload = {
  todayWorkCase: {
    workCaseId: 101,
    title: '주말 홀 서빙',
    workplaceName: '카페 봄',
    startsAt: '2026-07-22T01:00:00Z', // KST 10:00
    endsAt: '2026-07-22T09:00:00Z', // KST 18:00
    breakMinutes: 60,
    breakPaid: false,
    dailyWage: 90000,
    taxReference: {
      basisAmount: 85710,
      estimatedTaxAmount: 0,
      estimatedAfterTaxAmount: 85710
    },
    status: 'IN_PROGRESS',
    attendance: {
      checkedInAt: '2026-07-22T01:15:00Z',
      checkedOutAt: null,
      isLate: true,
      lateMinutes: 15
    },
    escrowStatus: 'HELD',
    settlementStatus: 'WAITING',
    settlementDueAt: null
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

  it('안심지갑 잔액·오늘의 알바·근무 경과 예상금액을 표시한다', async () => {
    const wrapper = mount(WorkerHomeView)
    await flushPromises()

    expect(wrapper.text()).toContain('320,000원') // 안심지갑 잔액(공용 wallet Store)
    expect(wrapper.text()).toContain('주말 홀 서빙') // 오늘의 알바
    expect(wrapper.text()).toContain('근무 경과 예상금액') // 참고값 카드(비금융 표시)
    expect(wrapper.text()).toContain('일급 90,000원') // dailyWage → agreedWage 로 매핑되는지 확인
    expect(wrapper.text()).toContain('예상 세액(참고)')
    expect(wrapper.text()).toContain('최종 지급액 85,710원 기준')
    expect(wrapper.text()).toContain('실제 지급액에서 세금을 차감하지 않습니다')
  })

  it('근무중 상태와 지각 여부를 서로 다른 뱃지로 함께 보여준다', async () => {
    const wrapper = mount(WorkerHomeView)
    await flushPromises()

    expect(wrapper.text()).toContain('근무중') // status='IN_PROGRESS' → WORK_CASE_STATUS 라벨
    expect(wrapper.text()).toContain('지각 15분') // attendance.isLate 파생 뱃지(상태값이 아니다)
  })

  it('오늘 근무가 없으면(todayWorkCase=null) 근무 경과 예상금액 카드를 숨긴다', async () => {
    getWorkerHome.mockResolvedValue({ todayWorkCase: null })
    const wrapper = mount(WorkerHomeView)
    await flushPromises()

    expect(wrapper.text()).not.toContain('근무 경과 예상금액')
    expect(wrapper.text()).not.toContain('예상 세액(참고)')
    expect(wrapper.text()).toContain('오늘은 예정된 알바가 없어요.')
  })

  it('정산 Snapshot 전 taxReference가 null이면 세금 참고 카드를 표시하지 않는다', async () => {
    const payload = structuredClone(homePayload)
    payload.todayWorkCase.taxReference = null
    getWorkerHome.mockResolvedValue(payload)

    const wrapper = mount(WorkerHomeView)
    await flushPromises()

    expect(wrapper.text()).not.toContain('예상 세액(참고)')
  })

  it.each(['NO_SHOW', 'CANCELED'])(
    '오늘 근무가 %s 상태면 근무 경과 예상금액 카드를 숨긴다(DEC-OPEN-DASHBOARD-BREAK)',
    async (status) => {
      const payload = structuredClone(homePayload)
      payload.todayWorkCase.status = status
      getWorkerHome.mockResolvedValue(payload)

      const wrapper = mount(WorkerHomeView)
      await flushPromises()

      expect(wrapper.text()).not.toContain('근무 경과 예상금액')
      expect(wrapper.text()).toContain('주말 홀 서빙') // 오늘의 알바 카드 자체는 계속 노출
    }
  )

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
