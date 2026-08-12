import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import WorkerWorkView from '@/views/worker/WorkerWorkView.vue'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

vi.mock('@/services/worker', () => ({ listWorkerWorkCases: vi.fn() }))

import { listWorkerWorkCases } from '@/services/worker'

const sampleWorkCase = {
  workCaseId: 101,
  workplaceName: '강남점',
  workDate: '2026-07-22',
  time: '10:00 ~ 18:00',
  dailyWage: 90000,
  status: 'IN_PROGRESS',
  settleStatus: 'HOLD'
}

describe('WorkerWorkView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    push.mockClear()
  })

  it('근무 내역을 목록으로 렌더한다', async () => {
    listWorkerWorkCases.mockResolvedValueOnce({ content: [sampleWorkCase], totalPages: 1 })
    const wrapper = mount(WorkerWorkView)
    await flushPromises()

    expect(wrapper.findAll('.work-case')).toHaveLength(1)
    expect(wrapper.text()).toContain('강남점')
    expect(wrapper.text()).toContain('90,000원')
  })

  it('내역이 없으면 빈 상태를 보여준다', async () => {
    listWorkerWorkCases.mockResolvedValueOnce({ content: [], totalPages: 1 })
    const wrapper = mount(WorkerWorkView)
    await flushPromises()

    expect(wrapper.findAll('.work-case')).toHaveLength(0)
    expect(wrapper.text()).toContain('아직 근무 내역이 없어요.')
  })

  it('미구현 operation은 빈 목록으로 가장하지 않고 준비 중 상태를 보여준다', async () => {
    listWorkerWorkCases.mockRejectedValueOnce({ code: 'FEATURE_UNAVAILABLE' })
    const wrapper = mount(WorkerWorkView)
    await flushPromises()

    expect(wrapper.text()).toContain('근무 내역은 현재 준비 중인 기능입니다.')
    expect(wrapper.text()).not.toContain('아직 근무 내역이 없어요.')
  })

  it('항목을 누르면 상세로 이동한다', async () => {
    listWorkerWorkCases.mockResolvedValueOnce({ content: [sampleWorkCase], totalPages: 1 })
    const wrapper = mount(WorkerWorkView)
    await flushPromises()

    await wrapper.find('.work-case-main').trigger('click')
    expect(push).toHaveBeenCalledWith('/worker/work/work-cases/101')
  })

  it('리스트에는 문의·신고 버튼을 두지 않는다', async () => {
    listWorkerWorkCases.mockResolvedValueOnce({ content: [sampleWorkCase], totalPages: 1 })
    const wrapper = mount(WorkerWorkView)
    await flushPromises()

    expect(wrapper.find('.help-btn').exists()).toBe(false)
  })
})
