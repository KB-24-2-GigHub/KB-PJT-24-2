import { describe, expect, it } from 'vitest'

import { calcElapsedPay } from '@/utils/earning'

const SHIFT = { agreedWage: 90000, workDate: '2026-07-22', startTime: '10:00', endTime: '18:00' }

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

  it('자정을 넘긴 근무도 경과 시간을 바르게 센다', () => {
    // 22:00 ~ 06:00 = 480분. 다음날 01:00 이면 180분 경과(37.5%)
    const r = calcElapsedPay({
      agreedWage: 90000,
      workDate: '2026-07-22',
      startTime: '22:00',
      endTime: '06:00',
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
      now: new Date('2026-07-22T21:00:00')
    })
    expect(r).toEqual({ elapsedPay: 0, progressRatio: 0 })
  })

  it('시각 형식이 잘못되면 0원 0%', () => {
    const r = calcElapsedPay({ agreedWage: 90000, startTime: '', endTime: '18:00' })
    expect(r).toEqual({ elapsedPay: 0, progressRatio: 0 })
  })

  it('시작과 종료가 같으면(0분 근무) 0원 0%', () => {
    const r = calcElapsedPay({
      agreedWage: 90000,
      workDate: '2026-07-22',
      startTime: '10:00',
      endTime: '10:00',
      now: new Date('2026-07-22T14:00:00')
    })
    expect(r).toEqual({ elapsedPay: 0, progressRatio: 0 })
  })

  it('workDate 가 없으면 오늘 날짜로 본다', () => {
    const r = calcElapsedPay({
      agreedWage: 90000,
      startTime: '10:00',
      endTime: '18:00',
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
      now: new Date('2026-07-22T14:00:00')
    })
    expect(r).toEqual({ elapsedPay: 45000, progressRatio: 0.5 })
  })

  it('파싱할 수 없는 시각 형식이면 0원 0%', () => {
    const r = calcElapsedPay({
      agreedWage: 90000,
      startTime: '알수없음',
      endTime: '18:00',
      now: new Date('2026-07-22T14:00:00')
    })
    expect(r).toEqual({ elapsedPay: 0, progressRatio: 0 })
  })
})
