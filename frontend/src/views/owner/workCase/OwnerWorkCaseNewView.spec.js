import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import AppDateFieldCalendar from '@/components/common/AppDateFieldCalendar.vue'
import AppTimeField from '@/components/common/AppTimeField.vue'
import OwnerWorkCaseNewView from '@/views/owner/workCase/OwnerWorkCaseNewView.vue'
import { useWorkplaceStore } from '@/stores/workplace'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

vi.mock('@/services/workCases', () => ({ createWorkCase: vi.fn() }))
vi.mock('@/services/workplaces', () => ({ listWorkplaces: vi.fn() }))

import { createWorkCase } from '@/services/workCases'
import { listWorkplaces } from '@/services/workplaces'

function mountView() {
  // BaseBottomSheet 는 <Teleport to="body"> 를 쓴다 — stub 하지 않으면 시트 내용이
  // wrapper 트리 밖(document.body)으로 옮겨져 find/findAll 이 찾지 못한다.
  return mount(OwnerWorkCaseNewView, { global: { stubs: { teleport: true } } })
}

/** "18:00" → { ampm: '오후', hour: 6, minute: 0 } — AppTimeField 다이얼이 쓰는 12시간제 표기. */
function to12h(hhmm) {
  const [hStr, mStr] = hhmm.split(':')
  const h = parseInt(hStr, 10)
  const ampm = h < 12 ? '오전' : '오후'
  const hour = h % 12 === 0 ? 12 : h % 12
  return { ampm, hour, minute: parseInt(mStr, 10) }
}

/**
 * 실제 사용자 흐름대로 채운다: 버튼 클릭 → 시트 열림 → 휠 항목 클릭 → 확인.
 * 컴포넌트에 값을 직접 emit 하면 시트가 안 열리거나 확인 버튼이 끊어져 있어도 테스트가
 * 통과해버린다 — 그 회귀를 잡으려고 버튼을 실제로 누른다.
 */
async function pickTime(fieldWrapper, hhmm) {
  const { ampm, hour, minute } = to12h(hhmm)
  await fieldWrapper.get('button.time-input').trigger('click')

  const cols = fieldWrapper.findAll('.wheel-col')
  const clickOption = async (col, text) => {
    const button = col.findAll('button').find((b) => b.text() === text)
    await button.trigger('click')
  }
  await clickOption(cols[0], ampm)
  await clickOption(cols[1], String(hour).padStart(2, '0'))
  await clickOption(cols[2], String(minute).padStart(2, '0'))

  await fieldWrapper
    .findAll('button')
    .find((b) => b.text() === '확인')
    .trigger('click')
}

/** 근무 날짜 캘린더는 기본으로 "오늘"이 속한 달을 연다 — 테스트는 시스템 시각을 2026-08-01로 고정해 둔다. */
async function pickDate(fieldWrapper, dateKey) {
  const day = String(Number(dateKey.split('-')[2]))
  await fieldWrapper.get('button.date-input').trigger('click')

  const cell = fieldWrapper
    .findAll('.cal-cell')
    .find((c) => !c.classes().includes('outside') && c.text() === day)
  await cell.trigger('click')

  await fieldWrapper
    .findAll('button')
    .find((b) => b.text() === '확인')
    .trigger('click')
}

function fillForm(wrapper) {
  const [timeStart, timeEnd] = wrapper.findAllComponents(AppTimeField)
  const dateField = wrapper.findComponent(AppDateFieldCalendar)
  const [title, breakMinutes, dailyWage] = [
    wrapper.find('input[type="text"]'),
    wrapper.find('input[placeholder="0"]'),
    wrapper.find('input[placeholder="원 단위로 입력"]')
  ]
  return {
    title,
    workDate: { setValue: (v) => pickDate(dateField, v) },
    startTime: { setValue: (v) => pickTime(timeStart, v) },
    endTime: { setValue: (v) => pickTime(timeEnd, v) },
    breakMinutes,
    dailyWage
  }
}

describe('OwnerWorkCaseNewView', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-01T00:00:00Z'))
    push.mockClear()
    createWorkCase.mockReset()
    listWorkplaces.mockReset().mockResolvedValue({
      content: [{ workplaceId: 7, name: '강남점', status: 'ACTIVE' }]
    })
    const workplaceStore = useWorkplaceStore()
    await workplaceStore.load()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('승인된 7개 필드만 보내고 성공하면 근태관리로 돌아간다', async () => {
    createWorkCase.mockResolvedValue({ workCaseId: 1 })
    const wrapper = mountView()
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
    const wrapper = mountView()
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
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-01T00:00:00Z'))
    createWorkCase.mockReset()
    listWorkplaces.mockReset().mockResolvedValue({
      content: [{ workplaceId: 7, name: '강남점', status: 'ACTIVE' }]
    })
    await useWorkplaceStore().load()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  async function submitWith(breakMinutes, { startTime = '09:00', endTime = '18:00' } = {}) {
    const wrapper = mountView()
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
