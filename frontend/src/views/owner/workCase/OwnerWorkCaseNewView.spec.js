import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import AppDateField from '@/components/common/AppDateField.vue'
import AppTimeField from '@/components/common/AppTimeField.vue'
import OwnerWorkCaseNewView from '@/views/owner/workCase/OwnerWorkCaseNewView.vue'
import { useWorkplaceStore } from '@/stores/workplace'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

vi.mock('@/services/workCases', () => ({ createWorkCase: vi.fn() }))
vi.mock('@/services/workplaces', () => ({ listWorkplaces: vi.fn() }))

import { createWorkCase } from '@/services/workCases'
import { listWorkplaces } from '@/services/workplaces'

function fillForm(wrapper) {
  const [timeStart, timeEnd] = wrapper.findAllComponents(AppTimeField)
  const dateField = wrapper.findComponent(AppDateField)
  const [title, breakMinutes, dailyWage] = [
    wrapper.find('input[type="text"]'),
    wrapper.find('input[placeholder="0"]'),
    wrapper.find('input[placeholder="원 단위로 입력"]')
  ]
  return {
    title,
    // AppDateField/AppTimeField는 네이티브 input이 아니라 v-model 컴포넌트라
    // setValue 대신 이렇게 채운다.
    workDate: { setValue: (v) => dateField.vm.$emit('update:modelValue', v) },
    startTime: { setValue: (v) => timeStart.vm.$emit('update:modelValue', v) },
    endTime: { setValue: (v) => timeEnd.vm.$emit('update:modelValue', v) },
    breakMinutes,
    dailyWage
  }
}

describe('OwnerWorkCaseNewView', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    push.mockClear()
    createWorkCase.mockReset()
    listWorkplaces.mockReset().mockResolvedValue({
      content: [{ workplaceId: 7, name: '강남점', status: 'ACTIVE' }]
    })
    const workplaceStore = useWorkplaceStore()
    await workplaceStore.load()
  })

  it('승인된 7개 필드만 보내고 성공하면 근태관리로 돌아간다', async () => {
    createWorkCase.mockResolvedValue({ workCaseId: 1 })
    const wrapper = mount(OwnerWorkCaseNewView)
    await flushPromises()

    const f = fillForm(wrapper)
    await f.title.setValue('주말 홀 서빙')
    await f.workDate.setValue('2026-08-10')
    await f.startTime.setValue('09:00')
    await f.endTime.setValue('18:00')
    await f.breakMinutes.setValue('60')
    await f.dailyWage.setValue('90000')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(createWorkCase).toHaveBeenCalledWith(7, {
      title: '주말 홀 서빙',
      workDate: '2026-08-10',
      startTime: '09:00',
      endTime: '18:00',
      breakMinutes: 60,
      breakPaid: false,
      dailyWage: 90000
    })
    expect(push).toHaveBeenCalledWith('/owner/attendance')
  })

  it('서버 fieldErrors를 같은 이름의 폼 필드 오류로 표시한다', async () => {
    createWorkCase.mockRejectedValue({
      response: {
        data: {
          fieldErrors: [{ field: 'dailyWage', reason: '일급은 1원 이상이어야 합니다.' }]
        }
      }
    })
    const wrapper = mount(OwnerWorkCaseNewView)
    await flushPromises()

    const f = fillForm(wrapper)
    await f.title.setValue('주말 홀 서빙')
    await f.workDate.setValue('2026-08-10')
    await f.startTime.setValue('09:00')
    await f.endTime.setValue('18:00')
    await f.dailyWage.setValue('90000')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('일급은 1원 이상이어야 합니다.')
  })
})

/**
 * 휴게시간 경계 — 서버 `requireValidWorkPeriod` 와 같은 자리에서 걸러야 한다.
 * 화면이 통과시키면 사용자는 제출한 뒤에야 400 을 본다.
 */
describe('OwnerWorkCaseNewView 휴게시간', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    createWorkCase.mockReset()
    listWorkplaces.mockReset().mockResolvedValue({
      content: [{ workplaceId: 7, name: '강남점', status: 'ACTIVE' }]
    })
    await useWorkplaceStore().load()
  })

  async function submitWith(breakMinutes, { startTime = '09:00', endTime = '18:00' } = {}) {
    const wrapper = mount(OwnerWorkCaseNewView)
    await flushPromises()

    const f = fillForm(wrapper)
    await f.title.setValue('주말 홀 서빙')
    await f.workDate.setValue('2026-08-10')
    await f.startTime.setValue(startTime)
    await f.endTime.setValue(endTime)
    await f.breakMinutes.setValue(breakMinutes)
    await f.dailyWage.setValue('90000')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    return wrapper
  }

  it('근무 시간을 넘는 휴게는 제출하지 않고 그 필드에 알린다', async () => {
    const wrapper = await submitWith('541') // 09:00~18:00 = 540분

    expect(createWorkCase).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('540분')
  })

  it('근무 시간과 정확히 같은 휴게는 서버와 같이 통과시킨다', async () => {
    createWorkCase.mockResolvedValue({ workCaseId: 1 })

    await submitWith('540')

    expect(createWorkCase).toHaveBeenCalledWith(7, expect.objectContaining({ breakMinutes: 540 }))
  })

  it('자정을 넘기는 근무는 실제 길이를 기준으로 본다', async () => {
    // 22:00~06:00 = 480분. 단순 뺄셈이면 음수라 어떤 휴게든 통과해 버린다.
    const wrapper = await submitWith('481', { startTime: '22:00', endTime: '06:00' })

    expect(createWorkCase).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('480분')
  })
})
