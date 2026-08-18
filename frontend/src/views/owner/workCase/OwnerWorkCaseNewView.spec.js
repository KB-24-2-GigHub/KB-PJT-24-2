import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import OwnerWorkCaseNewView from '@/views/owner/workCase/OwnerWorkCaseNewView.vue'
import { useWorkplaceStore } from '@/stores/workplace'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

vi.mock('@/services/workCases', () => ({ createWorkCase: vi.fn() }))
vi.mock('@/services/workplaces', () => ({ listWorkplaces: vi.fn() }))

import { createWorkCase } from '@/services/workCases'
import { listWorkplaces } from '@/services/workplaces'

function fillForm(wrapper) {
  const [title, workDate, breakMinutes, dailyWage] = [
    wrapper.find('input[type="text"]'),
    wrapper.find('input[type="date"]'),
    wrapper.find('input[placeholder="0"]'),
    wrapper.find('input[placeholder="원 단위로 입력"]')
  ]
  return { title, workDate, breakMinutes, dailyWage }
}

const chipByLabel = (wrapper, selector, label) =>
  wrapper.findAll(selector).find((node) => node.text() === label)

/**
 * 시각은 시트를 실제로 열어서 고른다. 컴포넌트에 값을 직접 주입하면 피커가 폼에 연결돼
 * 있지 않아도 테스트가 통과한다 — 확인하려는 게 바로 그 연결이다.
 * index 0 = 시작시간, 1 = 종료시간.
 */
async function pickTime(wrapper, index, { meridiem, hour, minute }) {
  await wrapper.findAll('button.time-trigger')[index].trigger('click')
  await chipByLabel(wrapper, '.meridiem-btn', meridiem).trigger('click')
  await chipByLabel(wrapper, '.hour-btn', hour).trigger('click')
  await chipByLabel(wrapper, '.minute-btn', minute).trigger('click')
  await wrapper.find('.picker-confirm').trigger('click')
}

const mountView = () => mount(OwnerWorkCaseNewView, { global: { stubs: { teleport: true } } })

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
    const wrapper = mountView()
    await flushPromises()

    const f = fillForm(wrapper)
    await f.title.setValue('주말 홀 서빙')
    await f.workDate.setValue('2026-08-10')
    await pickTime(wrapper, 0, { meridiem: '오전', hour: '9', minute: '00' })
    await pickTime(wrapper, 1, { meridiem: '오후', hour: '6', minute: '00' })
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
    const wrapper = mountView()
    await flushPromises()

    const f = fillForm(wrapper)
    await f.title.setValue('주말 홀 서빙')
    await f.workDate.setValue('2026-08-10')
    await pickTime(wrapper, 0, { meridiem: '오전', hour: '9', minute: '00' })
    await pickTime(wrapper, 1, { meridiem: '오후', hour: '6', minute: '00' })
    await f.dailyWage.setValue('90000')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('일급은 1원 이상이어야 합니다.')
  })
})

const AM_9 = { meridiem: '오전', hour: '9', minute: '00' }
const PM_6 = { meridiem: '오후', hour: '6', minute: '00' }

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

  async function submitWith(breakMinutes, { start = AM_9, end = PM_6 } = {}) {
    const wrapper = mountView()
    await flushPromises()

    const f = fillForm(wrapper)
    await f.title.setValue('주말 홀 서빙')
    await f.workDate.setValue('2026-08-10')
    await pickTime(wrapper, 0, start)
    await pickTime(wrapper, 1, end)
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
    const wrapper = await submitWith('481', {
      start: { meridiem: '오후', hour: '10', minute: '00' },
      end: { meridiem: '오전', hour: '6', minute: '00' }
    })

    expect(createWorkCase).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('480분')
  })
})

/**
 * 라이브 요약 — 시각을 고르는 동안 총·실근로·익일이 바로 보여야 한다. 제출 뒤에야
 * 알려 주면 자정 넘김 근무(SPEC-413-01)는 등록하고 나서 놀라게 된다.
 */
describe('OwnerWorkCaseNewView 근무시간 요약', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    listWorkplaces.mockReset().mockResolvedValue({
      content: [{ workplaceId: 7, name: '강남점', status: 'ACTIVE' }]
    })
    await useWorkplaceStore().load()
  })

  it('시각이 한쪽만 채워졌으면 요약을 보여주지 않는다', async () => {
    const wrapper = mountView()
    await flushPromises()

    await pickTime(wrapper, 0, { meridiem: '오전', hour: '9', minute: '00' })

    expect(wrapper.find('.period-summary').exists()).toBe(false)
  })

  it('무급 휴게를 뺀 실근로시간을 함께 보여준다', async () => {
    const wrapper = mountView()
    await flushPromises()

    await pickTime(wrapper, 0, { meridiem: '오전', hour: '9', minute: '00' })
    await pickTime(wrapper, 1, { meridiem: '오후', hour: '6', minute: '00' })
    await fillForm(wrapper).breakMinutes.setValue('60')

    expect(wrapper.find('.period-summary').text()).toBe('총 9시간 · 휴게 1시간 제외 실근로 8시간')
  })

  it('자정을 넘기면 익일 종료임을 알린다', async () => {
    const wrapper = mountView()
    await flushPromises()

    await pickTime(wrapper, 0, { meridiem: '오후', hour: '10', minute: '00' })
    await pickTime(wrapper, 1, { meridiem: '오전', hour: '6', minute: '00' })

    expect(wrapper.find('.period-summary').text()).toBe('총 8시간 · 익일 06:00 종료')
  })

  /* 상한 밖 시각은 에러로 알리는 대신 아예 고를 수 없어야 한다. */
  it('시작으로부터 16시간을 넘는 종료시각은 고를 수 없다', async () => {
    const wrapper = mountView()
    await flushPromises()

    await pickTime(wrapper, 0, { meridiem: '오후', hour: '6', minute: '00' })
    await wrapper.findAll('button.time-trigger')[1].trigger('click')
    await chipByLabel(wrapper, '.meridiem-btn', '오전').trigger('click')

    expect(chipByLabel(wrapper, '.hour-btn', '11').attributes('disabled')).toBeDefined()
    expect(chipByLabel(wrapper, '.hour-btn', '10').attributes('disabled')).toBeUndefined()
  })
})
