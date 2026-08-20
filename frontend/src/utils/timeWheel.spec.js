import { describe, expect, it } from 'vitest'
import { roundTimeToStep, to12Hour } from './timeWheel'

describe('roundTimeToStep', () => {
  it('step 배수인 값은 그대로 둔다', () => {
    expect(roundTimeToStep('09:20', 10)).toBe('09:20')
  })

  it('분이 60으로 반올림되면 시(hour)로 자리올림한다', () => {
    // Math.round(55/10)*10 = 60 — 분만 60%로 감싸면 자리올림 없이 09:00 이 돼버린다.
    expect(roundTimeToStep('14:55', 10)).toBe('15:00')
  })

  it('23시대에서 자리올림되면 다음날이 아니라 자정(00:00)으로 되감는다', () => {
    expect(roundTimeToStep('23:58', 10)).toBe('00:00')
  })

  it('가장 가까운 step으로 반올림한다(내림/올림 모두)', () => {
    expect(roundTimeToStep('09:23', 10)).toBe('09:20')
    expect(roundTimeToStep('09:27', 10)).toBe('09:30')
  })

  it.each(['', 'HH', 'HH:mm', undefined, null])(
    '형식이 아닌 값(%s)은 계산을 강행해 NaN:NaN 을 만들지 않고 원본을 그대로 돌려준다',
    (value) => {
      expect(roundTimeToStep(value, 10)).toBe(value)
    }
  )
})

describe('to12Hour', () => {
  it('오전/오후와 12시간제 시를 분해한다', () => {
    expect(to12Hour('09:20')).toEqual({ ampm: 'AM', hour12: 9, minute: 20 })
    expect(to12Hour('18:00')).toEqual({ ampm: 'PM', hour12: 6, minute: 0 })
  })

  it('자정·정오 경계를 12시로 표시한다', () => {
    expect(to12Hour('00:00')).toEqual({ ampm: 'AM', hour12: 12, minute: 0 })
    expect(to12Hour('12:00')).toEqual({ ampm: 'PM', hour12: 12, minute: 0 })
  })

  it('형식이 아닌 값은 null을 돌려준다', () => {
    expect(to12Hour('')).toBeNull()
    expect(to12Hour('HH:mm')).toBeNull()
  })
})
