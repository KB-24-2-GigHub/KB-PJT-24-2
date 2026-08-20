/**
 * 시각 다이얼(TimeWheelPicker)이 쓰는 반올림·12시간제 변환 유틸.
 *
 * TimeWheelPicker(다이얼 내부 표시)와 AppTimeField(닫힌 필드 표시)가 이 파일 하나를
 * 같이 써야 한다 — 따로 계산하면 필드에 보이는 값과 다이얼을 열었을 때 값이 어긋난다.
 *
 * @param {string} value "HH:mm"
 * @returns {{ hour: number, minute: number } | null} 형식이 아니면 null
 */
function parseHHmm(value) {
  const [hStr, mStr] = String(value ?? '').split(':')
  const hour = parseInt(hStr, 10)
  const minute = parseInt(mStr, 10)
  if (!Number.isInteger(hour) || !Number.isInteger(minute)) return null
  return { hour, minute }
}

/**
 * "HH:mm" 을 분 단위 총합으로 바꿔 반올림한 뒤 다시 시:분으로 되돌린다 — 55분처럼
 * step(10분) 경계를 넘는 값이 60분으로 반올림될 때 시(hour)로 자리올림되게 하고,
 * 23:55 같은 값이 다음날로 넘어가면 자정을 기준으로 되감는다.
 *
 * 형식이 아닌 값("", "HH", 잘못된 문자열 등)은 반올림을 시도하지 않고 원본을 그대로
 * 돌려준다 — 계산을 강행하면 "NaN:NaN" 이 화면에 조용히 노출될 수 있다.
 *
 * @param {string} value "HH:mm"
 * @param {number} step 분 단위(예: 10)
 * @returns {string} "HH:mm"(항상 step 의 배수인 분을 가진다), 형식이 아니면 원본 그대로
 */
export function roundTimeToStep(value, step) {
  const parsed = parseHHmm(value)
  if (!parsed) return value
  const totalMinutes = parsed.hour * 60 + parsed.minute
  const rounded = Math.round(totalMinutes / step) * step
  const wrapped = ((rounded % 1440) + 1440) % 1440
  const hh = String(Math.floor(wrapped / 60)).padStart(2, '0')
  const mm = String(wrapped % 60).padStart(2, '0')
  return `${hh}:${mm}`
}

/**
 * "HH:mm"(24시간제) → 12시간제 분해. TimeWheelPicker 의 다이얼 표시와 AppTimeField 의
 * 닫힌 필드 표시가 각자 이 계산을 다시 하면, 자정 처리 같은 경계에서 한쪽만 고쳤을 때
 * 둘이 다시 어긋날 수 있어 한 곳에 모았다.
 *
 * @param {string} value "HH:mm"
 * @returns {{ ampm: 'AM'|'PM', hour12: number, minute: number } | null} 형식이 아니면 null
 */
export function to12Hour(value) {
  const parsed = parseHHmm(value)
  if (!parsed) return null
  const { hour, minute } = parsed
  const ampm = hour < 12 ? 'AM' : 'PM'
  const hour12 = hour % 12 === 0 ? 12 : hour % 12
  return { ampm, hour12, minute }
}
