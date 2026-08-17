import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import WorkerWorkView from '@/views/worker/WorkerWorkView.vue'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

vi.mock('@/services/worker', () => ({ listWorkerWorkCases: vi.fn() }))

import { listWorkerWorkCases } from '@/services/worker'

// 실제 GET /worker/work-cases Page Item 계약(WorkerWorkCaseListItemResponse) 그대로.
const sampleWorkCase = {
  workCaseId: 101,
  title: '주말 홀 서빙',
  workplaceName: '강남점',
  startsAt: '2026-07-22T01:00:00Z', // KST 10:00
  endsAt: '2026-07-22T09:00:00Z', // KST 18:00
  breakMinutes: 60,
  breakPaid: false,
  dailyWage: 90000,
  status: 'IN_PROGRESS',
  attendance: {
    checkedInAt: '2026-07-22T01:00:00Z',
    checkedOutAt: null,
    isLate: false,
    lateMinutes: null
  },
  escrowStatus: 'HELD',
  settlementStatus: 'WAITING',
  settlementDueAt: null
}

function samplePage(content) {
  return { content, page: { number: 0, size: 20, totalElements: content.length, totalPages: 1 } }
}

/** Page Envelope 를 직접 지정한다 — 여러 Page 상황을 만든다. */
function pageOf(content, number, totalPages) {
  return { content, page: { number, size: 20, totalElements: totalPages * 20, totalPages } }
}

function moreButton(wrapper) {
  return wrapper.findAll('button').find((button) => button.text().includes('더 보기'))
}

describe('WorkerWorkView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    push.mockClear()
    // mockResolvedValueOnce 는 큐라서, 어떤 테스트가 쌓아두고 쓰지 않은 값이 다음 테스트의
    // 첫 조회로 흘러든다. 리셋하지 않으면 테스트 순서에 따라 결과가 달라진다.
    listWorkerWorkCases.mockReset()
  })

  it('근무 내역을 목록으로 렌더한다', async () => {
    listWorkerWorkCases.mockResolvedValueOnce(samplePage([sampleWorkCase]))
    const wrapper = mount(WorkerWorkView)
    await flushPromises()

    expect(wrapper.findAll('.work-case')).toHaveLength(1)
    expect(wrapper.text()).toContain('강남점')
    expect(wrapper.text()).toContain('90,000원')
  })

  it('근무지 기준 시각·정산 상태를 표시한다', async () => {
    listWorkerWorkCases.mockResolvedValueOnce(samplePage([sampleWorkCase]))
    const wrapper = mount(WorkerWorkView)
    await flushPromises()

    expect(wrapper.text()).toContain('10:00 ~ 18:00') // startsAt/endsAt → Asia/Seoul 벽시계
    expect(wrapper.text()).toContain('2026.07.22')
    expect(wrapper.text()).toContain('근무중') // status
    expect(wrapper.text()).toContain('정산대기') // settlementStatus='WAITING'
  })

  it('내역이 없으면 빈 상태를 보여준다', async () => {
    listWorkerWorkCases.mockResolvedValueOnce(samplePage([]))
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
    listWorkerWorkCases.mockResolvedValueOnce(samplePage([sampleWorkCase]))
    const wrapper = mount(WorkerWorkView)
    await flushPromises()

    await wrapper.find('.work-case-main').trigger('click')
    expect(push).toHaveBeenCalledWith('/worker/work/work-cases/101')
  })

  it('리스트에는 문의·신고 버튼을 두지 않는다', async () => {
    listWorkerWorkCases.mockResolvedValueOnce(samplePage([sampleWorkCase]))
    const wrapper = mount(WorkerWorkView)
    await flushPromises()

    expect(wrapper.find('.help-btn').exists()).toBe(false)
  })

  it('settlementStatus가 SCHEDULED이고 settlementDueAt이 있으면 정산 예정 시각을 표시한다', async () => {
    listWorkerWorkCases.mockResolvedValueOnce(
      samplePage([
        {
          ...sampleWorkCase,
          settlementStatus: 'SCHEDULED',
          settlementDueAt: '2026-08-21T00:00:00Z'
        }
      ])
    )
    const wrapper = mount(WorkerWorkView)
    await flushPromises()

    expect(wrapper.text()).toContain('2026.08.21 09:00 지급 예정')
  })

  it('settlementDueAt이 없으면 예정 시각을 표시하지 않는다', async () => {
    listWorkerWorkCases.mockResolvedValueOnce(samplePage([sampleWorkCase]))
    const wrapper = mount(WorkerWorkView)
    await flushPromises()

    expect(wrapper.text()).not.toContain('지급 예정')
  })

  it('due_at은 지급 완료·실패 후에도 남아있는 값이라 SCHEDULED가 아니면 지급 예정을 표시하지 않는다', async () => {
    listWorkerWorkCases.mockResolvedValueOnce(
      samplePage([
        {
          ...sampleWorkCase,
          settlementStatus: 'COMPLETED',
          settlementDueAt: '2026-08-21T00:00:00Z'
        },
        {
          ...sampleWorkCase,
          workCaseId: 102,
          settlementStatus: 'FAILED',
          settlementDueAt: '2026-08-21T00:00:00Z'
        }
      ])
    )
    const wrapper = mount(WorkerWorkView)
    await flushPromises()

    expect(wrapper.text()).not.toContain('지급 예정')
  })

  /**
   * 목록 API 는 기본 20건 Page 다. 화면이 첫 Page 만 읽으면 21번째부터는 표시도 오류도
   * 없이 사라진다 — 사용자는 그 기록이 없다고 믿게 된다.
   */
  it('다음 Page 가 남아 있으면 더 보기로 이어 붙인다', async () => {
    listWorkerWorkCases.mockResolvedValueOnce(pageOf([sampleWorkCase], 0, 2))
    const wrapper = mount(WorkerWorkView)
    await flushPromises()

    expect(wrapper.findAll('.work-case')).toHaveLength(1)

    listWorkerWorkCases.mockResolvedValueOnce(
      pageOf([{ ...sampleWorkCase, workCaseId: 202, workplaceName: '홍대점' }], 1, 2)
    )
    await moreButton(wrapper).trigger('click')
    await flushPromises()

    // 갈아끼우지 않고 이어 붙여야 앞 Page 가 사라지지 않는다.
    expect(wrapper.findAll('.work-case')).toHaveLength(2)
    expect(wrapper.text()).toContain('강남점')
    expect(wrapper.text()).toContain('홍대점')
    expect(listWorkerWorkCases).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }))
  })

  it('마지막 Page 에서는 더 보기를 노출하지 않는다', async () => {
    listWorkerWorkCases.mockResolvedValueOnce(pageOf([sampleWorkCase], 0, 2))
    const wrapper = mount(WorkerWorkView)
    await flushPromises()

    expect(moreButton(wrapper)).toBeTruthy()

    listWorkerWorkCases.mockResolvedValueOnce(
      pageOf([{ ...sampleWorkCase, workCaseId: 202 }], 1, 2)
    )
    await moreButton(wrapper).trigger('click')
    await flushPromises()

    expect(moreButton(wrapper)).toBeUndefined()
  })

  it('Page 가 하나뿐이면 처음부터 더 보기가 없다', async () => {
    listWorkerWorkCases.mockResolvedValueOnce(samplePage([sampleWorkCase]))
    const wrapper = mount(WorkerWorkView)
    await flushPromises()

    expect(moreButton(wrapper)).toBeUndefined()
  })

  it('더 보기 실패는 이미 불러온 목록을 지우지 않는다', async () => {
    listWorkerWorkCases.mockResolvedValueOnce(pageOf([sampleWorkCase], 0, 2))
    const wrapper = mount(WorkerWorkView)
    await flushPromises()

    listWorkerWorkCases.mockRejectedValueOnce(new Error('server error'))
    await moreButton(wrapper).trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.work-case')).toHaveLength(1)
    expect(wrapper.text()).toContain('강남점')
  })

  it('401·403·409·5xx 등 오류에서 빈 상태 대신 오류 상태를 보여주고 이전 성공 상태를 표시하지 않는다', async () => {
    listWorkerWorkCases.mockRejectedValueOnce(new Error('server error'))
    const wrapper = mount(WorkerWorkView)
    await flushPromises()

    expect(wrapper.text()).toContain('근무 내역을 불러오지 못했습니다.')
    expect(wrapper.text()).not.toContain('아직 근무 내역이 없어요.')
    expect(wrapper.findAll('.work-case')).toHaveLength(0)
  })
})
