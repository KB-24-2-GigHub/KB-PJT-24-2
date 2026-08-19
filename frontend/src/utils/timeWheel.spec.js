import { describe, expect, it } from 'vitest'
import { roundTimeToStep } from './timeWheel'

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
})
