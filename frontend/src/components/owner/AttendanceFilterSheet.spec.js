import { describe, expect, it } from 'vitest'

import { buildAttendanceFilterParams } from '@/components/owner/AttendanceFilterSheet.vue'
import { WORK_CASE_STATUS_FILTER } from '@/constants/workCaseStatus'

describe('buildAttendanceFilterParams', () => {
  it('기본값(전체·빈 값)은 아무 파라미터도 만들지 않는다', () => {
    expect(buildAttendanceFilterParams()).toEqual({})
  })

  it("status 가 'ALL' 이면 제외하고, 그 외에는 전달한다", () => {
    expect(buildAttendanceFilterParams({ status: 'ALL' })).not.toHaveProperty('status')
    expect(buildAttendanceFilterParams({ status: 'IN_PROGRESS' }).status).toBe('IN_PROGRESS')
  })

  it('검색어는 trim 하고, 빈 문자열은 제외한다', () => {
    expect(buildAttendanceFilterParams({ keyword: '  서빙  ' }).keyword).toBe('서빙')
    expect(buildAttendanceFilterParams({ keyword: '   ' })).not.toHaveProperty('keyword')
  })

  it('기간을 채우면 서비스와 같은 키(from·to)로 넣는다', () => {
    const params = buildAttendanceFilterParams({ from: '2026-07-01', to: '2026-07-31' })
    expect(params).toEqual({ from: '2026-07-01', to: '2026-07-31' })
  })

  it('기간 한쪽만 채워도 그 값만 전달한다', () => {
    expect(buildAttendanceFilterParams({ from: '2026-07-01' })).toEqual({ from: '2026-07-01' })
  })

  it('초안에 없는 키는 만들어내지 않는다', () => {
    expect(Object.keys(buildAttendanceFilterParams({ keyword: '서빙' }))).toEqual(['keyword'])
  })

  // 목록 Query 계약(API_SPEC 'OWNER 근무 관리')에 sort 가 없다 — 정렬은 서버가 정한다.
  it('정렬 값을 넣어도 서버 파라미터로 새어 나가지 않는다', () => {
    expect(buildAttendanceFilterParams({ sort: 'OLDEST' })).not.toHaveProperty('sort')
  })
})

describe('WORK_CASE_STATUS_FILTER', () => {
  it("'전체' 를 맨 앞에 두고 8단계 상태를 전이 순서로 잇는다", () => {
    expect(WORK_CASE_STATUS_FILTER.map((o) => o.value)).toEqual([
      'ALL',
      'DRAFT',
      'ACCEPTED',
      'READY',
      'IN_PROGRESS',
      'CHECK_OUT_MISSING',
      'COMPLETED',
      'NO_SHOW',
      'CANCELED'
    ])
  })

  it('라벨은 상태 상수에서 파생된다(하드코딩 금지)', () => {
    const labels = Object.fromEntries(WORK_CASE_STATUS_FILTER.map((o) => [o.value, o.label]))
    expect(labels.ALL).toBe('전체')
    expect(labels.IN_PROGRESS).toBe('근무중')
    expect(labels.CHECK_OUT_MISSING).toBe('확인필요')
    expect(labels.CANCELED).toBe('취소')
  })
})
