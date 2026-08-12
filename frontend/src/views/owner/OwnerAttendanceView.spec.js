/**
 * OWNER 근태관리 요약 카드 계약 테스트(#168).
 * CHECK_OUT_MISSING 은 NO_SHOW·COMPLETED 와 상호 배타적인 별도 상태라, 요약 카드가
 * 세 값을 각각 따로 보여줘야 하고 서로 합산돼서는 안 된다.
 */
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/services/workplaces', () => ({ listWorkplaces: vi.fn() }))
vi.mock('@/services/workCases', () => ({
  getWorkCaseSummary: vi.fn(),
  listWorkCases: vi.fn(),
  createInvite: vi.fn()
}))

import { listWorkplaces } from '@/services/workplaces'
import { getWorkCaseSummary, listWorkCases } from '@/services/workCases'
import OwnerAttendanceView from '@/views/owner/OwnerAttendanceView.vue'

const SUMMARY = {
  draft: 1,
  accepted: 2,
  ready: 3,
  inProgress: 4,
  checkOutMissing: 5,
  completed: 6,
  noShow: 7
}

function mountView() {
  return mount(OwnerAttendanceView, { global: { stubs: { teleport: true } } })
}

describe('OwnerAttendanceView 요약 카드', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    listWorkplaces.mockReset().mockResolvedValue({
      content: [{ workplaceId: 1, name: '강남점', status: 'ACTIVE' }],
      page: { number: 0, size: 100, totalElements: 1, totalPages: 1 }
    })
    getWorkCaseSummary.mockReset().mockResolvedValue({ ...SUMMARY })
    listWorkCases.mockReset().mockResolvedValue({
      content: [],
      page: { number: 0, size: 20, totalElements: 0, totalPages: 0 }
    })
  })

  it('CHECK_OUT_MISSING 을 NO_SHOW·COMPLETED 와 구분된 카드로 보여준다', async () => {
    const wrapper = mountView()
    await flushPromises()

    const cards = wrapper.findAll('.stat')
    const byLabel = Object.fromEntries(
      cards.map((card) => [card.find('.stat-label').text(), card.find('.stat-value').text().trim()])
    )

    expect(byLabel['퇴근 확인 필요']).toBe('5')
    expect(byLabel['완료']).toBe('6')
    expect(byLabel['노쇼']).toBe('7')
  })

  it('퇴근 확인 필요 카드를 누르면 CHECK_OUT_MISSING 상태로만 다시 조회한다', async () => {
    const wrapper = mountView()
    await flushPromises()
    listWorkCases.mockClear()

    const card = wrapper
      .findAll('.stat')
      .find((c) => c.find('.stat-label').text() === '퇴근 확인 필요')
    await card.trigger('click')
    await flushPromises()

    expect(listWorkCases).toHaveBeenCalledWith(
      1,
      expect.objectContaining({ status: 'CHECK_OUT_MISSING' })
    )
  })
})
