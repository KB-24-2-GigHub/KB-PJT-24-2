/**
 * 근무 경과 예상금액 표시 계산 — 화면 표시 전용 참고 추정치. 지갑·정산·예치금 금액이 아니다.
 *
 * 명세 DASH-001 비고: "earning은 표시 계산값. 지각 차감·실제 지갑 증가 없음".
 * 여기서 만드는 값은 어떤 경우에도 지갑 잔액·정산 금액이 되지 않는다.
 * 금액 기준값(일급·잔액·정산액)의 권위는 서버에 있고, 이 파일은 서버가 준
 * 기준값으로부터 시간 경과에 따라 변하는 표시값만 파생한다.
 */

import { parseWallClockMinutes as toMinutes } from '@/utils/format'

const MINUTES_PER_DAY = 24 * 60

/** 근무 시작 시각을 Date 로 만든다. workDate 가 없으면 now 의 날짜를 쓴다. */
function resolveStartAt(workDate, startMinutes, now) {
  const base = /^\d{4}-\d{2}-\d{2}$/.test(workDate ?? '')
    ? new Date(`${workDate}T00:00:00`)
    : new Date(now)
  base.setHours(0, 0, 0, 0)
  base.setMinutes(startMinutes)
  return base
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value))
}

/**
 * 근무 시작 시각부터 지금까지 경과한 비율만큼 일급을 적립해 보여준다.
 * 무급 휴게는 전체 구간에 균등 비례로 녹아 있다(휴게 시각 필드가 스키마에 없음).
 */
export function calcElapsedPay({ agreedWage, workDate, startTime, endTime, now = new Date() }) {
  const wage = Number(agreedWage) || 0
  const start = toMinutes(startTime)
  const end = toMinutes(endTime)
  if (start === null || end === null) return { elapsedPay: 0, progressRatio: 0 }

  // 시작과 종료가 같은 근무는 등록 단계에서 24시간으로 해석돼 길이 상한에 걸린다
  // (SPEC-413-01). 그래도 여기까지 온 값은 이상 데이터이므로 0으로 접는다 — 24시간으로
  // 세면 잘못된 데이터에 하루치 적립 진행률이 붙는다.
  if (start === end) return { elapsedPay: 0, progressRatio: 0 }

  // 종료가 시작보다 이르면 자정을 넘긴 근무다.
  const totalMinutes = end > start ? end - start : end + MINUTES_PER_DAY - start

  const startAt = resolveStartAt(workDate, start, now)
  const elapsed = clamp((now.getTime() - startAt.getTime()) / 60000, 0, totalMinutes)
  const progressRatio = elapsed / totalMinutes

  return { elapsedPay: Math.floor(wage * progressRatio), progressRatio }
}
