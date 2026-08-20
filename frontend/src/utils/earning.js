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
 * 근무 시작~종료 구간의 분 단위 길이와 예정 시작·종료 시각을 계산한다.
 * 시작·종료 시각이 형식 오류이거나 같으면(등록 단계 24시간 상한 이상 데이터,
 * SPEC-413-01) null을 반환해 호출부가 0으로 접게 한다.
 */
function resolveShiftWindow(workDate, startTime, endTime, now) {
  const start = toMinutes(startTime)
  const end = toMinutes(endTime)
  if (start === null || end === null || start === end) return null

  // 종료가 시작보다 이르면 자정을 넘긴 근무다.
  const totalMinutes = end > start ? end - start : end + MINUTES_PER_DAY - start
  const scheduledStartAt = resolveStartAt(workDate, start, now)
  const scheduledEndAt = new Date(scheduledStartAt.getTime() + totalMinutes * 60000)

  return { totalMinutes, scheduledStartAt, scheduledEndAt }
}

/**
 * 근무 시작 시각부터 지금까지 경과한 비율만큼 일급을 적립해 보여준다.
 * 무급 휴게는 전체 구간에 균등 비례로 녹아 있다(휴게 시각 필드가 스키마에 없음).
 *
 * 출근 QR 체크인(attendance.checkedInAt) 전에는 근무가 시작된 것이 아니므로 적립하지
 * 않는다. 지각으로 체크인이 예정 시각보다 늦으면 실제 체크인 시각부터 적립을 센다.
 */
export function calcElapsedPay({
  agreedWage,
  workDate,
  startTime,
  endTime,
  checkedInAt,
  now = new Date()
}) {
  const wage = Number(agreedWage) || 0
  const window = resolveShiftWindow(workDate, startTime, endTime, now)
  if (!window) return { elapsedPay: 0, progressRatio: 0 }
  if (!checkedInAt) return { elapsedPay: 0, progressRatio: 0 }

  const { totalMinutes, scheduledStartAt, scheduledEndAt } = window
  const checkedInAtDate = new Date(checkedInAt)
  const startAt = checkedInAtDate > scheduledStartAt ? checkedInAtDate : scheduledStartAt

  // now를 예정 종료 시각에서 자른다 — 지각 체크인으로 startAt이 밀린 경우에도, 실제
  // 시계상 종료 시각을 지나면 더 이상 올라가면 안 된다(#466). totalMinutes만큼의 상한은
  // startAt이 밀린 만큼 종료 시각을 넘어서까지 허용해버려 이 상한만으로는 못 막는다.
  const cappedNow = Math.min(now.getTime(), scheduledEndAt.getTime())
  const elapsed = clamp((cappedNow - startAt.getTime()) / 60000, 0, totalMinutes)
  const progressRatio = elapsed / totalMinutes

  return { elapsedPay: Math.floor(wage * progressRatio), progressRatio }
}

/**
 * 체크인 전, 예정 시작 시각이 지난 만큼(지각) 진행률 바를 채우기 위한 비율.
 * 적립액은 0을 유지하되(calcElapsedPay), 진행률 바는 지각 경과를 보여준다 —
 * SecuredEarningCard가 체크인 전엔 주황, 체크인 후엔 이 값 대신 progressRatio로 노랑을 쓴다.
 *
 * 예정 종료 시각에서 자른다 — 체크인 없이 종료 시각을 넘겨도 더 채워지지 않는다.
 */
export function calcLateProgress({ workDate, startTime, endTime, now = new Date() }) {
  const window = resolveShiftWindow(workDate, startTime, endTime, now)
  if (!window) return 0

  const { totalMinutes, scheduledStartAt, scheduledEndAt } = window
  const cappedNow = Math.min(now.getTime(), scheduledEndAt.getTime())
  const lateMinutes = clamp((cappedNow - scheduledStartAt.getTime()) / 60000, 0, totalMinutes)
  return lateMinutes / totalMinutes
}
