import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import WorkerReportView from '@/views/worker/workCase/WorkerReportView.vue'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { workCaseId: '101' } }),
  useRouter: () => ({ back: vi.fn() })
}))

vi.mock('@/services/workCases', () => ({ createReport: vi.fn(), listReports: vi.fn() }))

import { createReport, listReports } from '@/services/workCases'
import { useUiStore } from '@/stores/ui'

const EMPTY_PAGE = {
  content: [],
  page: { number: 0, size: 20, totalElements: 0, totalPages: 0 }
}

describe('WorkerReportView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    createReport.mockReset().mockResolvedValue({ reportId: 1 })
    listReports.mockReset().mockResolvedValue(EMPTY_PAGE)
  })

  it('법적 판단이 아닌 DEMO와 예치금 보류를 안내한다', async () => {
    const wrapper = mount(WorkerReportView)
    await flushPromises()

    expect(wrapper.text()).toContain('예치금 흐름을 일시 보류')
    expect(wrapper.text()).toContain('법적 판단이나 자문이 아닙니다')
  })

  it('제목과 경위가 모두 있어야 제출할 수 있다', async () => {
    const wrapper = mount(WorkerReportView)
    await flushPromises()
    const submit = () => wrapper.find('button.submit')

    await wrapper.find('input').setValue('임금 확인')
    expect(submit().attributes('disabled')).toBeDefined()

    await wrapper.find('textarea').setValue('약정 일급 지급 여부를 확인해주세요.')
    expect(submit().attributes('disabled')).toBeUndefined()
  })

  it('신고를 제출한 뒤 같은 화면에서 저장 상태를 다시 읽는다', async () => {
    listReports.mockResolvedValueOnce(EMPTY_PAGE).mockResolvedValueOnce({
      ...EMPTY_PAGE,
      content: [
        {
          reportId: 1,
          title: '임금 확인',
          content: '약정 일급 지급 여부를 확인해주세요.',
          status: 'OPEN',
          createdAt: '2026-08-15T01:00:00Z',
          demoReview: null
        }
      ]
    })
    const wrapper = mount(WorkerReportView)
    await flushPromises()

    await wrapper.find('input').setValue(' 임금 확인 ')
    await wrapper.find('textarea').setValue(' 약정 일급 지급 여부를 확인해주세요. ')
    await wrapper.find('button.submit').trigger('click')
    await flushPromises()

    expect(createReport).toHaveBeenCalledWith('101', {
      title: '임금 확인',
      content: '약정 일급 지급 여부를 확인해주세요.'
    })
    expect(listReports).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('접수됨 · AI 검토 대기')
  })

  it('추가 자료 요청 결과와 사유 코드를 저장 응답 그대로 표시한다', async () => {
    listReports.mockResolvedValueOnce({
      ...EMPTY_PAGE,
      content: [
        {
          reportId: 2,
          title: '근태 확인',
          content: '퇴근 기록을 확인해주세요.',
          status: 'UNDER_REVIEW',
          createdAt: '2026-08-15T01:00:00Z',
          demoReview: {
            source: 'SIMULATED_LLM',
            status: 'COMPLETED',
            decision: 'NEEDS_MORE_INFO',
            reasonCodes: ['ATTENDANCE_EVIDENCE_NEEDED'],
            summary: '근태 기록을 추가로 확인해야 합니다.',
            confidence: 0.7,
            reviewedAt: '2026-08-15T01:00:02Z'
          }
        }
      ]
    })
    const wrapper = mount(WorkerReportView)
    await flushPromises()

    expect(wrapper.text()).toContain('추가 자료 필요 · 보류 유지')
    expect(wrapper.text()).toContain('근태 기록을 추가로 확인해야 합니다.')
    expect(wrapper.text()).toContain('ATTENDANCE_EVIDENCE_NEEDED')
    expect(wrapper.find('button.submit').attributes('disabled')).toBeDefined()
  })

  it('재시도 소진으로 검토가 실패하면 양측이 이해할 수 있는 보류 상태를 표시한다', async () => {
    listReports.mockResolvedValueOnce({
      ...EMPTY_PAGE,
      content: [
        {
          reportId: 3,
          title: '임금 확인',
          content: '지급 여부를 확인해주세요.',
          status: 'UNDER_REVIEW',
          createdAt: '2026-08-15T01:00:00Z',
          demoReview: {
            source: 'SIMULATED_LLM',
            status: 'FAILED',
            decision: null,
            reasonCodes: [],
            summary: null,
            confidence: null,
            reviewedAt: '2026-08-15T01:00:02Z'
          }
        }
      ]
    })
    const wrapper = mount(WorkerReportView)
    await flushPromises()

    expect(wrapper.text()).toContain('검토 지연 · 보류 유지')
    expect(wrapper.text()).toContain('외부 DEMO 검토가 지연되어 예치금 보류를 유지하고 있습니다.')
  })

  it('중복 신고는 처리 중 상태를 확인하라고 안내한다', async () => {
    createReport.mockRejectedValueOnce({ code: 'DISPUTE_ALREADY_OPEN' })
    const wrapper = mount(WorkerReportView)
    await flushPromises()

    await wrapper.find('input').setValue('임금 확인')
    await wrapper.find('textarea').setValue('약정 일급 지급 여부를 확인해주세요.')
    await wrapper.find('button.submit').trigger('click')
    await flushPromises()

    expect(useUiStore().toasts.at(-1)).toMatchObject({
      message: '이미 처리 중인 분쟁이 있습니다. 아래 상태를 확인해주세요.',
      type: 'warning'
    })
  })
})
