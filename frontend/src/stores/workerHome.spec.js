/**
 * WORKER 홈 Store 계약 테스트(#168).
 * GET /worker/home 은 todayWorkCase 하나만 준다(WorkerHomeResponse.java) — earning은
 * 여기서 파생한다. 지각은 attendance.isLate 파생값이지 상태값이 아니다.
 */
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/services/worker', () => ({ getWorkerHome: vi.fn() }))

import { useWorkerHomeStore } from '@/stores/workerHome'
import { getWorkerHome } from '@/services/worker'

const RAW_TODAY_WORK_CASE = {
  workCaseId: 101,
  title: '주말 홀 서빙',
  workplaceName: '카페 봄',
  startsAt: '2026-07-22T01:15:00Z', // KST 10:15
  endsAt: '2026-07-22T09:00:00Z', // KST 18:00
  breakMinutes: 60,
  breakPaid: false,
  dailyWage: 90000,
  expectedNetAmount: 88500,
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

describe('useWorkerHomeStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    getWorkerHome.mockReset()
  })

  it('todayWorkCase에 Asia/Seoul 벽시계 workDate/startTime/endTime을 붙인다', async () => {
    getWorkerHome.mockResolvedValue({ todayWorkCase: RAW_TODAY_WORK_CASE })
    const store = useWorkerHomeStore()

    await store.loadHome()

    expect(store.todayWorkCase.workDate).toBe('2026-07-22')
    expect(store.todayWorkCase.startTime).toBe('10:15')
    expect(store.todayWorkCase.endTime).toBe('18:00')
    // 원본 필드(status·attendance 등)는 그대로 남아 있어야 StatusChip 등이 계속 읽을 수 있다.
    expect(store.todayWorkCase.status).toBe('IN_PROGRESS')
    expect(store.todayWorkCase.attendance.isLate).toBe(true)
  })

  it('startsAt이 자정을 넘겨 서울 날짜가 바뀌는 경우 workDate가 서울 기준으로 밀린다', async () => {
    // 07-21 23:30 UTC = 07-22 08:30 KST — UTC 날짜와 서울 날짜가 갈리는 경계 케이스.
    getWorkerHome.mockResolvedValue({
      todayWorkCase: { ...RAW_TODAY_WORK_CASE, startsAt: '2026-07-21T23:30:00Z' }
    })
    const store = useWorkerHomeStore()

    await store.loadHome()

    expect(store.todayWorkCase.workDate).toBe('2026-07-22')
    expect(store.todayWorkCase.startTime).toBe('08:30')
  })

  it('todayWorkCase로부터 earning을 파생한다(지각은 attendance.isLate 파생값)', async () => {
    getWorkerHome.mockResolvedValue({ todayWorkCase: RAW_TODAY_WORK_CASE })
    const store = useWorkerHomeStore()

    await store.loadHome()

    expect(store.earning).toEqual({
      agreedWage: 90000,
      expectedNetAmount: 88500,
      isLate: true,
      lateMinutes: 15
    })
  })

  it('지각이 아니면 lateMinutes를 null 대신 0으로 채운다', async () => {
    getWorkerHome.mockResolvedValue({
      todayWorkCase: {
        ...RAW_TODAY_WORK_CASE,
        attendance: {
          checkedInAt: RAW_TODAY_WORK_CASE.attendance.checkedInAt,
          checkedOutAt: null,
          isLate: false,
          lateMinutes: null
        }
      }
    })
    const store = useWorkerHomeStore()

    await store.loadHome()

    expect(store.earning.isLate).toBe(false)
    expect(store.earning.lateMinutes).toBe(0)
  })

  it('오늘 근무가 없으면(todayWorkCase=null) todayWorkCase·earning 모두 null이다', async () => {
    getWorkerHome.mockResolvedValue({ todayWorkCase: null })
    const store = useWorkerHomeStore()

    await store.loadHome()

    expect(store.todayWorkCase).toBeNull()
    expect(store.earning).toBeNull()
  })

  it('조회 실패는 error에 담고 이전 값을 지우지 않는다', async () => {
    getWorkerHome.mockRejectedValue(new Error('network'))
    const store = useWorkerHomeStore()

    await store.loadHome()

    expect(store.error).toBeInstanceOf(Error)
    expect(store.todayWorkCase).toBeNull()
  })
})
