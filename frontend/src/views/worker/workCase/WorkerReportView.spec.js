import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import WorkerReportView from '@/views/worker/workCase/WorkerReportView.vue'

const back = vi.fn()
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { workCaseId: '101' } }),
  useRouter: () => ({ back })
}))

vi.mock('@/services/workCases', () => ({ createReport: vi.fn() }))

import { createReport } from '@/services/workCases'
import { useUiStore } from '@/stores/ui'

describe('WorkerReportView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    back.mockClear()
    createReport.mockReset()
    createReport.mockResolvedValue({ reportId: 1 })
  })

  it('신고 전 사장님 선연락을 권장하는 안내를 보여준다', () => {
    const wrapper = mount(WorkerReportView)

    expect(wrapper.text()).toContain('신고 절차에는 시간이 소요될 수 있어요')
    expect(wrapper.text()).toContain('신고 전에 먼저 사장님과 연락해보는 것을 권장드려요')
    expect(wrapper.text()).not.toContain('정산 진행에는 영향을 주지 않습니다')
  })

  it('경위서가 최소 길이 미만이면 제출 버튼이 비활성화된다', async () => {
    const wrapper = mount(WorkerReportView)
    const submit = () => wrapper.find('button.submit')

    expect(submit().attributes('disabled')).toBeDefined()

    await wrapper.find('textarea').setValue('짧음')
    expect(submit().attributes('disabled')).toBeDefined()
  })

  it('충분히 작성하면 신고를 제출하고 뒤로 이동한다', async () => {
    const wrapper = mount(WorkerReportView)

    await wrapper.find('textarea').setValue('임금이 제때 지급되지 않았습니다.')
    expect(wrapper.find('button.submit').attributes('disabled')).toBeUndefined()

    await wrapper.find('button.submit').trigger('click')
    await flushPromises()

    expect(createReport).toHaveBeenCalledWith('101', {
      content: '임금이 제때 지급되지 않았습니다.'
    })
    expect(back).toHaveBeenCalled()
  })

  it('미구현 신고 operation은 성공으로 가장하지 않고 준비 중으로 안내한다', async () => {
    createReport.mockRejectedValueOnce({ code: 'FEATURE_UNAVAILABLE' })
    const wrapper = mount(WorkerReportView)

    await wrapper.find('textarea').setValue('임금이 제때 지급되지 않았습니다.')
    await wrapper.find('button.submit').trigger('click')
    await flushPromises()

    expect(back).not.toHaveBeenCalled()
    expect(useUiStore().toasts.at(-1)).toMatchObject({
      message: '임금분쟁 신고는 현재 준비 중인 기능입니다.',
      type: 'info'
    })
  })
})
