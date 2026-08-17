/**
 * 근무 경과 예상금액 표시 계산 — 화면 표시 전용 참고 추정치. 지갑·정산·예치금 금액이 아니다.
 *
 * 명세 DASH-001 비고: "earning은 표시 계산값. 지각 차감·실제 지갑 증가 없음".
 * 여기서 만드는 값은 어떤 경우에도 지갑 잔액·정산 금액이 되지 않는다.
 * 금액 기준값(일급·잔액·정산액)의 권위는 서버에 있고, 이 파일은 서버가 준
 * 기준값으로부터 시간 경과에 따라 변하는 표시값만 파생한다.
 *
 * calcDailyTax 는 BE 가 expectedNetAmount(DASH-001)를 구현할 때의 참조 구현이다.
 */

const MINUTES_PER_DAY = 24 * 60

const TAX_FREE_LIMIT = 150000 // 일용직 근로소득공제 — 일당 15만원까지 비과세
const INCOME_TAX_RATE = 0.027 // 소득세 (6% × (1 - 55% 세액공제))
const LOCAL_TAX_RATE = 0.0027 // 지방소득세 (소득세의 10%)
const MIN_WITHHOLDING = 1000 // 소액부징수 기준

/**
 * "HH:mm", "HH:mm:ss", ISO 문자열 → 자정 기준 분. 파싱할 수 없으면 null.
 * format.js 의 formatTime 과 동일한 방식으로 ISO 는 로컬 시:분을 읽는다 — 두 유틸이 서로 다른
 * 입력을 받아들이면 같은 값이 화면에 따라 다르게 보일 수 있어 맞춰둔다.
 */
function toMinutes(value) {
  if (typeof value !== 'string') return null

  const m = /^(\d{1,2}):(\d{2})/.exec(value)
  if (m) {
    const h = Number(m[1])
    const min = Number(m[2])
    if (h > 23 || min > 59) return null
    return h * 60 + min
  }

  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return null
  return d.getHours() * 60 + d.getMinutes()
}

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

/** 10원 미만 절사 */
function floorTo10(value) {
  return Math.floor(value / 10) * 10
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

/** 일용직 원천징수 기준 예상 실수령액. 일급 전액 기준이며 경과 시간과 무관하다. */
export function calcDailyTax(agreedWage) {
  const wage = Number(agreedWage) || 0
  const taxable = Math.max(0, wage - TAX_FREE_LIMIT)

  let incomeTax = floorTo10(taxable * INCOME_TAX_RATE)
  let localTax = floorTo10(taxable * LOCAL_TAX_RATE)

  // 소액부징수 — 절사 후 소득세가 1,000원 미만이면 징수하지 않는다.
  if (incomeTax < MIN_WITHHOLDING) {
    incomeTax = 0
    localTax = 0
  }

  const totalTax = incomeTax + localTax
  return { incomeTax, localTax, totalTax, expectedNetAmount: wage - totalTax }
}
