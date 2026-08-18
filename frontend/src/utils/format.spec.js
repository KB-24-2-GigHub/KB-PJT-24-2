/**
 * 근무 시간 표기 단위 테스트.
 *
 * 시각만 보여주면 `23:00 ~ 01:00` 이 2시간 근무인지 22시간 근무인지 구분되지 않는다.
 * SPEC-413-01 이 자정 넘김 근무를 허용하면서 종료가 다음 날임을 표기하도록 정했다.
 */
import { describe, expect, it } from 'vitest'

import { formatSeoulTimeRange, formatWorkPeriodSummary } from '@/utils/format'

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

/**
 * 근무 등록/수정 폼의 라이브 요약.
 *
 * 시작·종료만 보여주는 폼은 "22:00~06:00" 이 8시간인지 16시간인지, 무급 휴게를 빼면
 * 얼마가 남는지를 제출 뒤에야 알려 준다. 이 요약은 입력 중에 그 결과를 그대로 보여주는
 * 자리라, 총·실근로·익일 세 값이 각각 어긋나지 않는지가 전부다.
 */
describe('formatWorkPeriodSummary', () => {
  const summary = (over = {}) =>
    formatWorkPeriodSummary({
      startTime: '09:00',
      endTime: '18:00',
      breakMinutes: 0,
      breakPaid: false,
      ...over
    })

  it('휴게가 없으면 총 근무시간만 보여준다', () => {
    expect(summary()).toBe('총 9시간')
  })

  it('무급 휴게는 실근로에서 뺀다', () => {
    expect(summary({ breakMinutes: 60 })).toBe('총 9시간 · 휴게 1시간 제외 실근로 8시간')
  })

  it('유급 휴게는 빼지 않는다', () => {
    expect(summary({ breakMinutes: 60, breakPaid: true })).toBe('총 9시간')
  })

  it('자정을 넘기면 종료가 다음 날임을 알린다', () => {
    expect(summary({ startTime: '22:00', endTime: '06:00' })).toBe('총 8시간 · 익일 06:00 종료')
  })

  it('자정을 넘기면서 휴게가 있으면 둘 다 보여준다', () => {
    expect(summary({ startTime: '22:00', endTime: '06:00', breakMinutes: 30 })).toBe(
      '총 8시간 · 휴게 30분 제외 실근로 7시간 30분 · 익일 06:00 종료'
    )
  })

  it('시각이 아직 덜 채워졌으면 아무것도 보여주지 않는다', () => {
    expect(summary({ startTime: '' })).toBe('')
    expect(summary({ endTime: '' })).toBe('')
    expect(summary({ endTime: '25:00' })).toBe('')
  })

  /* 시작과 종료가 같으면 0분이 아니라 24시간이고, 그건 등록될 수 없는 값이다(SPEC-413-01). */
  it('시작과 종료가 같으면 요약할 값이 없다', () => {
    expect(summary({ startTime: '09:00', endTime: '09:00' })).toBe('')
  })

  /* 휴게가 근무보다 길어도 음수 시간을 만들어 내지는 않는다. */
  it('무급 휴게가 총 시간보다 길면 실근로를 0분으로 접는다', () => {
    expect(summary({ breakMinutes: 600 })).toBe('총 9시간 · 휴게 10시간 제외 실근로 0분')
  })
})
