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

import AttendanceCalendar from '@/components/owner/AttendanceCalendar.vue'
import AttendanceViewToggle from '@/components/owner/AttendanceViewToggle.vue'
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
  noShow: 7,
  canceled: 8
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

    const cards = wrapper.findAll('.stat-pill')
    const byLabel = Object.fromEntries(
      cards.map((card) => [card.find('.stat-label').text(), card.find('.stat-value').text().trim()])
    )

    expect(byLabel['퇴근 미확인']).toBe('5')
    expect(byLabel['근무완료']).toBe('6')
    expect(byLabel['노쇼']).toBe('7')
  })

  it('퇴근 미확인 카드를 누르면 CHECK_OUT_MISSING 상태로만 다시 조회한다', async () => {
    const wrapper = mountView()
    await flushPromises()
    listWorkCases.mockClear()

    const card = wrapper
      .findAll('.stat-pill')
      .find((c) => c.find('.stat-label').text() === '퇴근 미확인')
    await card.trigger('click')
    await flushPromises()

    expect(listWorkCases).toHaveBeenCalledWith(
      1,
      expect.objectContaining({ status: 'CHECK_OUT_MISSING' })
    )
  })

  it('"전체" pill은 CANCELED 를 포함한 합계를 보여준다', async () => {
    const wrapper = mountView()
    await flushPromises()

    const total = wrapper.findAll('.stat-pill').find((c) => c.find('.stat-label').text() === '전체')

    // 7버킷 합(1+2+3+4+5+6+7=28) + canceled(8) = 36 — 카드에 없는 CANCELED 도 더한다.
    expect(total.find('.stat-value').text().trim()).toBe('36')
  })

  it('"전체" pill을 누르면 status 필터 없이(취소 포함 전체) 다시 조회한다', async () => {
    const wrapper = mountView()
    await flushPromises()

    const checkOutMissing = wrapper
      .findAll('.stat-pill')
      .find((c) => c.find('.stat-label').text() === '퇴근 미확인')
    await checkOutMissing.trigger('click')
    await flushPromises()
    listWorkCases.mockClear()

    const total = wrapper.findAll('.stat-pill').find((c) => c.find('.stat-label').text() === '전체')
    await total.trigger('click')
    await flushPromises()

    const params = listWorkCases.mock.calls.at(-1)[1]
    expect(params.status).toBeUndefined()
  })
})

describe('OwnerAttendanceView 목록 Page 2+', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    listWorkplaces.mockReset().mockResolvedValue({
      content: [{ workplaceId: 1, name: '강남점', status: 'ACTIVE' }],
      page: { number: 0, size: 100, totalElements: 1, totalPages: 1 }
    })
    getWorkCaseSummary.mockReset().mockResolvedValue({ ...SUMMARY })
  })

  it('더 보기를 누르면 다음 Page 를 이어 붙이고, 마지막 Page 에서는 버튼이 사라진다', async () => {
    listWorkCases.mockReset().mockResolvedValueOnce({
      content: [{ workCaseId: 1, title: '1페이지 근무', status: 'READY', workDate: '2026-07-01' }],
      page: { number: 0, size: 1, totalElements: 2, totalPages: 2 }
    })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('1페이지 근무')
    expect(wrapper.find('.load-more').exists()).toBe(true)

    listWorkCases.mockResolvedValueOnce({
      content: [{ workCaseId: 2, title: '2페이지 근무', status: 'READY', workDate: '2026-07-02' }],
      page: { number: 1, size: 1, totalElements: 2, totalPages: 2 }
    })
    await wrapper.find('.load-more').trigger('click')
    await flushPromises()

    expect(listWorkCases).toHaveBeenLastCalledWith(1, expect.objectContaining({ page: 1 }))
    // 1페이지 결과는 그대로 남고 2페이지 결과가 이어 붙는다 — 교체가 아니라 누적이다.
    expect(wrapper.text()).toContain('1페이지 근무')
    expect(wrapper.text()).toContain('2페이지 근무')
    expect(wrapper.find('.load-more').exists()).toBe(false)
  })
})

describe('OwnerAttendanceView 빈 결과', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    listWorkplaces.mockReset().mockResolvedValue({
      content: [{ workplaceId: 1, name: '강남점', status: 'ACTIVE' }],
      page: { number: 0, size: 100, totalElements: 1, totalPages: 1 }
    })
    getWorkCaseSummary.mockReset().mockResolvedValue({ ...SUMMARY })
  })

  it('필터 없이 근무가 없으면 첫 근무 추가를 안내한다', async () => {
    listWorkCases.mockReset().mockResolvedValue({
      content: [],
      page: { number: 0, size: 20, totalElements: 0, totalPages: 0 }
    })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('등록된 근무가 없습니다.')
    expect(wrapper.text()).not.toContain('조건에 맞는 근무가 없습니다.')
  })

  it('필터가 걸린 채로 결과가 없으면 조건 안내 문구로 구분한다', async () => {
    listWorkCases.mockReset().mockResolvedValue({
      content: [],
      page: { number: 0, size: 20, totalElements: 0, totalPages: 0 }
    })
    const wrapper = mountView()
    await flushPromises()

    const card = wrapper
      .findAll('.stat-pill')
      .find((c) => c.find('.stat-label').text() === '퇴근 미확인')
    await card.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('조건에 맞는 근무가 없습니다.')
  })
})

describe('OwnerAttendanceView 캘린더 뷰 · 서울 날짜 경계', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    listWorkplaces.mockReset().mockResolvedValue({
      content: [{ workplaceId: 1, name: '강남점', status: 'ACTIVE' }],
      page: { number: 0, size: 100, totalElements: 1, totalPages: 1 }
    })
    getWorkCaseSummary.mockReset().mockResolvedValue({ ...SUMMARY })
    listWorkCases.mockReset().mockResolvedValue({
      content: [
        // 월 경계에 걸친 근무 두 건 — 그 달의 마지막 날과 다음 달 첫날.
        { workCaseId: 1, title: '7월 말일 근무', status: 'READY', workDate: '2026-07-31' },
        { workCaseId: 2, title: '8월 첫날 근무', status: 'READY', workDate: '2026-08-01' }
      ],
      page: { number: 0, size: 100, totalElements: 2, totalPages: 1 }
    })
  })

  it('캘린더로 전환하면 보고 있는 달의 from~to(서울 날짜 키)로만 조회한다', async () => {
    const wrapper = mountView()
    await flushPromises()
    listWorkCases.mockClear()

    await wrapper.findComponent(AttendanceViewToggle).vm.$emit('update:modelValue', 'calendar')
    await flushPromises()

    const monthKey = listWorkCases.mock.calls.at(-1)[1]
    expect(monthKey.from).toMatch(/^\d{4}-\d{2}-01$/)
    expect(monthKey.to).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    // from 이 가리키는 달의 말일이 to 다 — 다음 달 1일이 섞여 들어오지 않는다.
    expect(monthKey.from.slice(0, 7)).toBe(monthKey.to.slice(0, 7))
  })

  it('날짜를 고르면 그 날짜 키와 정확히 일치하는 근무만 보여준다(하루 밀리지 않는다)', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.findComponent(AttendanceViewToggle).vm.$emit('update:modelValue', 'calendar')
    await flushPromises()

    await wrapper.findComponent(AttendanceCalendar).vm.$emit('update:selectedDate', '2026-07-31')
    await flushPromises()

    expect(wrapper.text()).toContain('7월 말일 근무')
    expect(wrapper.text()).not.toContain('8월 첫날 근무')
  })
})
