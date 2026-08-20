import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  WORK_CASE_STATUS,
  WORK_CASE_STATUS_FILTER,
  WORK_CASE_SUMMARY,
  displayWorkCaseStatus,
  emptyWorkCaseSummary
} from '@/constants/workCaseStatus'

// RF-12부터 Mock 데이터는 operation selector로 명시 선택한다.
async function importWithMock() {
  vi.resetModules()
  vi.doMock('@/services/mockOperations', () => ({ isMockOperationEnabled: () => true }))
  return import('@/services/workCases')
}

// ck_work_cases_status(V202607311429) 가 허용하는 8개. 순서는 그 제약의 나열 순서다.
const PERSISTED_WORK_CASE_STATUSES = [
  'DRAFT',
  'ACCEPTED',
  'READY',
  'IN_PROGRESS',
  'CHECK_OUT_MISSING',
  'COMPLETED',
  'NO_SHOW',
  'CANCELED'
]

describe('work-case status contract', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.doUnmock('@/services/mockOperations')
  })

  it('maps every persisted status without exposing INVITED', () => {
    expect(Object.keys(WORK_CASE_STATUS)).toEqual(PERSISTED_WORK_CASE_STATUSES)
    expect(WORK_CASE_STATUS).not.toHaveProperty('INVITED')
  })

  it('uses ACCEPTED instead of INVITED in the attendance summary', () => {
    expect(WORK_CASE_SUMMARY).toContainEqual({ key: 'accepted', status: 'ACCEPTED' })
    expect(WORK_CASE_SUMMARY).not.toContainEqual({ key: 'invited', status: 'INVITED' })
    expect(emptyWorkCaseSummary()).toEqual({
      draft: 0,
      accepted: 0,
      ready: 0,
      inProgress: 0,
      checkOutMissing: 0,
      completed: 0,
      noShow: 0
    })
  })

  it('keeps mock list and summary responses free of INVITED', async () => {
    const { getWorkCaseSummary, listWorkCases } = await importWithMock()
    const [{ content }, summary] = await Promise.all([listWorkCases(1), getWorkCaseSummary(1)])

    expect(content.map(({ status }) => status)).not.toContain('INVITED')
    expect(summary).toHaveProperty('accepted')
    expect(summary).not.toHaveProperty('invited')
  })

  it('keeps CHECK_OUT_MISSING a separate summary bucket from NO_SHOW and COMPLETED', () => {
    const keysByStatus = Object.fromEntries(WORK_CASE_SUMMARY.map((b) => [b.status, b.key]))

    expect(keysByStatus.CHECK_OUT_MISSING).toBe('checkOutMissing')
    expect(keysByStatus.CHECK_OUT_MISSING).not.toBe(keysByStatus.NO_SHOW)
    expect(keysByStatus.CHECK_OUT_MISSING).not.toBe(keysByStatus.COMPLETED)
  })

  it('mock summary reports checkOutMissing as its own field', async () => {
    const { getWorkCaseSummary } = await importWithMock()
    const summary = await getWorkCaseSummary(1)

    expect(summary).toHaveProperty('checkOutMissing')
  })

  it('keeps the mock list aligned with the live API item contract', async () => {
    const { listWorkCases } = await importWithMock()
    const { content } = await listWorkCases(1)

    for (const item of content) {
      expect(item).toEqual(
        expect.objectContaining({
          workCaseId: expect.any(Number),
          workDate: expect.any(String),
          startsAt: expect.stringMatching(/Z$/),
          endsAt: expect.stringMatching(/Z$/),
          dailyWage: expect.any(Number),
          status: expect.any(String)
        })
      )
      expect(item).not.toHaveProperty('startTime')
      expect(item).not.toHaveProperty('endTime')
      expect(item).not.toHaveProperty('workerName')
      expect(item).not.toHaveProperty('workplaceId')
      if (item.worker) {
        expect(item.worker).toEqual({ workerId: expect.any(Number), name: expect.any(String) })
      }
    }

    const { content: workerMatches } = await listWorkCases(1, { keyword: '이알바' })
    expect(workerMatches.map(({ workCaseId }) => workCaseId)).toEqual([101])
  })

  // 실제 API는 목록 Item에 capability를 두지 않으므로 Mock도 별도 필드 없이 DRAFT 항목만 둔다.
  it('keeps multiple DRAFT items in the mock fixture', async () => {
    const { listWorkCases } = await importWithMock()
    const { content } = await listWorkCases(1)
    const firstDraft = content.find(({ workCaseId }) => workCaseId === 102)
    const secondDraft = content.find(({ workCaseId }) => workCaseId === 104)

    expect(firstDraft).toMatchObject({ status: 'DRAFT' })
    expect(secondDraft).toMatchObject({ status: 'DRAFT' })
  })
})

describe('displayWorkCaseStatus (LATE 파생 표시)', () => {
  const now = new Date('2026-08-20T01:30:00Z')

  it('READY이고 체크인 전인데 시작 시각이 지났으면 LATE를 반환한다', () => {
    const workCase = { status: 'READY', startsAt: '2026-08-20T01:00:00Z', attendance: null }
    expect(displayWorkCaseStatus(workCase, now)).toBe('LATE')
  })

  it('READY이고 시작 시각 전이면 그대로 READY다', () => {
    const workCase = { status: 'READY', startsAt: '2026-08-20T02:00:00Z', attendance: null }
    expect(displayWorkCaseStatus(workCase, now)).toBe('READY')
  })

  it('이미 체크인했으면 시작 시각이 지났어도 LATE로 바꾸지 않는다', () => {
    const workCase = {
      status: 'READY',
      startsAt: '2026-08-20T01:00:00Z',
      attendance: { checkedInAt: '2026-08-20T01:10:00Z' }
    }
    expect(displayWorkCaseStatus(workCase, now)).toBe('READY')
  })

  it('READY가 아닌 다른 상태는 그대로 반환한다', () => {
    expect(
      displayWorkCaseStatus({ status: 'IN_PROGRESS', startsAt: '2026-08-20T01:00:00Z' }, now)
    ).toBe('IN_PROGRESS')
    expect(
      displayWorkCaseStatus({ status: 'NO_SHOW', startsAt: '2026-08-20T01:00:00Z' }, now)
    ).toBe('NO_SHOW')
  })

  it('LATE는 파생 표시값이라 필터·요약이 순회하는 WORK_CASE_STATUS·WORK_CASE_STATUS_FILTER에 섞이지 않는다', () => {
    expect(WORK_CASE_STATUS).not.toHaveProperty('LATE')
    expect(WORK_CASE_STATUS_FILTER.map((f) => f.value)).not.toContain('LATE')
  })
})
