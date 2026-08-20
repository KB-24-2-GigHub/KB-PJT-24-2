/**
 * 근무 시간 표기 단위 테스트.
 *
 * 시각만 보여주면 `23:00 ~ 01:00` 이 2시간 근무인지 22시간 근무인지 구분되지 않는다.
 * SPEC-413-01 이 자정 넘김 근무를 허용하면서 종료가 다음 날임을 표기하도록 정했다.
 */
import { describe, expect, it } from 'vitest'

import { formatSeoulTimeRange } from '@/utils/format'

// 서울은 UTC+9. 아래 Instant 들은 모두 그 기준으로 계산한 값이다.
const SEOUL_0900 = '2026-08-10T00:00:00Z'
const SEOUL_1800 = '2026-08-10T09:00:00Z'
const SEOUL_2300 = '2026-08-10T14:00:00Z'
const SEOUL_NEXT_0100 = '2026-08-10T16:00:00Z'
const SEOUL_NEXT_0700 = '2026-08-10T22:00:00Z'

describe('formatSeoulTimeRange', () => {
  it('같은 날 끝나는 근무는 익일 표기 없이 그대로 보여준다', () => {
    expect(formatSeoulTimeRange(SEOUL_0900, SEOUL_1800)).toBe('09:00 ~ 18:00')
  })

  /*
   * 이 케이스가 핵심이다. 서울 23:00~다음날 01:00 은 UTC 로는 2026-08-10T14:00Z ~
   * 2026-08-10T16:00Z 라 **UTC 날짜가 같다**. UTC 나 브라우저 로컬 TZ 로 날짜를 비교하면
   * 자정을 넘긴 근무인데도 익일이 붙지 않는다. 서울 날짜 키로 비교해야만 잡힌다.
   */
  it('서울 기준으로 자정을 넘기면 익일을 붙인다(UTC 날짜가 같아도)', () => {
    expect(SEOUL_2300.slice(0, 10)).toBe(SEOUL_NEXT_0100.slice(0, 10)) // UTC 날짜 동일
    expect(formatSeoulTimeRange(SEOUL_2300, SEOUL_NEXT_0100)).toBe('23:00 ~ 익일 01:00')
  })

  it('아침까지 이어지는 야간 근무도 익일로 표기한다', () => {
    expect(formatSeoulTimeRange(SEOUL_2300, SEOUL_NEXT_0700)).toBe('23:00 ~ 익일 07:00')
  })

  it('값이 없으면 빈 문자열을 준다', () => {
    expect(formatSeoulTimeRange(null, null)).toBe('')
    expect(formatSeoulTimeRange('', '')).toBe('')
  })

  it('한쪽만 있으면 익일을 붙이지 않는다', () => {
    // 비교할 날짜가 없으면 자정을 넘겼는지 알 수 없다. 모르는 것을 단정하지 않는다.
    expect(formatSeoulTimeRange(SEOUL_2300, null)).toBe('23:00 ~ ')
  })
})
