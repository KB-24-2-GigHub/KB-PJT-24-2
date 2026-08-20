import { describe, expect, it } from 'vitest'

import { calcElapsedPay, calcLateProgress } from '@/utils/earning'

const SHIFT = {
  agreedWage: 90000,
  workDate: '2026-07-22',
  startTime: '10:00',
  endTime: '18:00',
  checkedInAt: '2026-07-22T10:00:00'
}

describe('calcElapsedPay', () => {
  it('근무 시작 전이면 0원 0%', () => {
    const r = calcElapsedPay({ ...SHIFT, now: new Date('2026-07-22T09:00:00') })
    expect(r).toEqual({ elapsedPay: 0, progressRatio: 0 })
  })

  it('구간 절반이 지나면 일급의 절반이 적립된다', () => {
    const r = calcElapsedPay({ ...SHIFT, now: new Date('2026-07-22T14:00:00') })
    expect(r.progressRatio).toBeCloseTo(0.5)
    expect(r.elapsedPay).toBe(45000)
  })

  it('1원 미만은 절사한다', () => {
    // 241분 / 480분 × 90,000 = 45,187.5
    const r = calcElapsedPay({ ...SHIFT, now: new Date('2026-07-22T14:01:00') })
    expect(r.elapsedPay).toBe(45187)
  })

  it('근무 종료 후에는 일급 전액에서 멈춘다', () => {
    const r = calcElapsedPay({ ...SHIFT, now: new Date('2026-07-22T20:00:00') })
    expect(r).toEqual({ elapsedPay: 90000, progressRatio: 1 })
  })

  it('아직 출근 QR 체크인 전이면(checkedInAt=null) 지각 등으로 예정 시각이 지나도 0원 0%', () => {
    // #466 — 지각으로 아직 체크인하지 않았는데도 예상 금액이 올라가던 문제
    const r = calcElapsedPay({ ...SHIFT, checkedInAt: null, now: new Date('2026-07-22T14:00:00') })
    expect(r).toEqual({ elapsedPay: 0, progressRatio: 0 })
  })

  it('지각 체크인이면 예정 시각이 아니라 실제 체크인 시각부터 적립한다', () => {
    // 10:00 시작 예정, 10:30 지각 체크인, 14:00 기준 → 210분/480분 경과
    const r = calcElapsedPay({
      ...SHIFT,
      checkedInAt: '2026-07-22T10:30:00',
      now: new Date('2026-07-22T14:00:00')
    })
    expect(r.progressRatio).toBeCloseTo(210 / 480)
    expect(r.elapsedPay).toBe(39375)
  })

  it('예정 시각보다 일찍 체크인해도 예정 시작 시각 이전으로는 적립하지 않는다', () => {
    const r = calcElapsedPay({
      ...SHIFT,
      checkedInAt: '2026-07-22T09:30:00',
      now: new Date('2026-07-22T10:30:00')
    })
    expect(r.progressRatio).toBeCloseTo(30 / 480)
  })

  it('자정을 넘긴 근무도 경과 시간을 바르게 센다', () => {
    // 22:00 ~ 06:00 = 480분. 다음날 01:00 이면 180분 경과(37.5%)
    const r = calcElapsedPay({
      agreedWage: 90000,
      workDate: '2026-07-22',
      startTime: '22:00',
      endTime: '06:00',
      checkedInAt: '2026-07-22T22:00:00',
      now: new Date('2026-07-23T01:00:00')
    })
    expect(r.progressRatio).toBeCloseTo(0.375)
    expect(r.elapsedPay).toBe(33750)
  })

  it('자정을 넘긴 근무도 시작 전이면 0원', () => {
    const r = calcElapsedPay({
      agreedWage: 90000,
      workDate: '2026-07-22',
      startTime: '22:00',
      endTime: '06:00',
      checkedInAt: '2026-07-22T22:00:00',
      now: new Date('2026-07-22T21:00:00')
    })
    expect(r).toEqual({ elapsedPay: 0, progressRatio: 0 })
  })

  it('시각 형식이 잘못되면 0원 0%', () => {
    const r = calcElapsedPay({
      agreedWage: 90000,
      startTime: '',
      endTime: '18:00',
      checkedInAt: '2026-07-22T10:00:00'
    })
    expect(r).toEqual({ elapsedPay: 0, progressRatio: 0 })
  })

  it('시작과 종료가 같으면(0분 근무) 0원 0%', () => {
    const r = calcElapsedPay({
      agreedWage: 90000,
      workDate: '2026-07-22',
      startTime: '10:00',
      endTime: '10:00',
      checkedInAt: '2026-07-22T10:00:00',
      now: new Date('2026-07-22T14:00:00')
    })
    expect(r).toEqual({ elapsedPay: 0, progressRatio: 0 })
  })

  it('workDate 가 없으면 오늘 날짜로 본다', () => {
    const r = calcElapsedPay({
      agreedWage: 90000,
      startTime: '10:00',
      endTime: '18:00',
      checkedInAt: '2026-07-22T10:00:00',
      now: new Date('2026-07-22T14:00:00')
    })
    expect(r.elapsedPay).toBe(45000)
  })

  it('ISO 시각 문자열도 "HH:mm" 과 동일한 결과를 낸다', () => {
    const r = calcElapsedPay({
      agreedWage: 90000,
      workDate: '2026-07-22',
      startTime: '2026-07-22T10:00:00',
      endTime: '2026-07-22T18:00:00',
      checkedInAt: '2026-07-22T10:00:00',
      now: new Date('2026-07-22T14:00:00')
    })
    expect(r).toEqual({ elapsedPay: 45000, progressRatio: 0.5 })
  })

  it('파싱할 수 없는 시각 형식이면 0원 0%', () => {
    const r = calcElapsedPay({
      agreedWage: 90000,
      startTime: '알수없음',
      endTime: '18:00',
      checkedInAt: '2026-07-22T10:00:00',
      now: new Date('2026-07-22T14:00:00')
    })
    expect(r).toEqual({ elapsedPay: 0, progressRatio: 0 })
  })

  it('지각 체크인이어도 예정 종료 시각을 지나면 더 이상 오르지 않는다', () => {
    // #466 — startAt(체크인 시각)이 밀린 만큼 totalMinutes 상한이 종료 시각을 넘어서까지
    // 허용해 지각 체크인 근무는 종료 뒤에도 계속 오르던 문제.
    // 10:00 시작 예정, 11:00 지각 체크인(1시간 지각) → 실제 근무 가능 구간은 7시간뿐이라
    // 종료 시각(18:00)에 도달해도 8시간 몫 100%가 아니라 420/480(87.5%)에서 멈춘다 —
    // 지각한 만큼 이 근무에서 낼 수 있는 최대치도 줄어드는 게 맞는 계산이다.
    const atEnd = calcElapsedPay({
      ...SHIFT,
      checkedInAt: '2026-07-22T11:00:00',
      now: new Date('2026-07-22T18:00:00')
    })
    expect(atEnd.progressRatio).toBeCloseTo(420 / 480)

    // 종료 시각을 더 지나도(20:00) 18:00 시점과 똑같아야 한다 — 계속 오르면 버그.
    const afterEnd = calcElapsedPay({
      ...SHIFT,
      checkedInAt: '2026-07-22T11:00:00',
      now: new Date('2026-07-22T20:00:00')
    })
    expect(afterEnd).toEqual(atEnd)
  })
})

describe('calcLateProgress', () => {
  const WINDOW = { workDate: '2026-07-22', startTime: '10:00', endTime: '18:00' }

  it('시작 시각 전이면 0이다', () => {
    expect(calcLateProgress({ ...WINDOW, now: new Date('2026-07-22T09:00:00') })).toBe(0)
  })

  it('시작 시각이 지난 만큼(지각분/전체 근무분) 채워진다', () => {
    // 10:00 시작, 10:48 기준 → 48분/480분
    const r = calcLateProgress({ ...WINDOW, now: new Date('2026-07-22T10:48:00') })
    expect(r).toBeCloseTo(48 / 480)
  })

  it('예정 종료 시각을 지나도(체크인 없이 방치) 1을 넘지 않는다', () => {
    const r = calcLateProgress({ ...WINDOW, now: new Date('2026-07-23T00:00:00') })
    expect(r).toBe(1)
  })

  it('시각 형식이 잘못되거나 시작·종료가 같으면 0이다', () => {
    expect(calcLateProgress({ startTime: '', endTime: '18:00' })).toBe(0)
    expect(
      calcLateProgress({
        workDate: '2026-07-22',
        startTime: '10:00',
        endTime: '10:00',
        now: new Date('2026-07-22T14:00:00')
      })
    ).toBe(0)
  })
})
