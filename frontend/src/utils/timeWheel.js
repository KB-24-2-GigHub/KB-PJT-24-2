/**
 * 시각 다이얼(TimeWheelPicker)이 쓰는 반올림 유틸.
 *
 * "HH:mm" 을 분 단위 총합으로 바꿔 반올림한 뒤 다시 시:분으로 되돌린다 — 55분처럼
 * step(10분) 경계를 넘는 값이 60분으로 반올림될 때 시(hour)로 자리올림되게 하고,
 * 23:55 같은 값이 다음날로 넘어가면 자정을 기준으로 되감는다.
 *
 * TimeWheelPicker(다이얼 내부 표시)와 AppTimeField(닫힌 필드 표시)가 이 함수 하나를
 * 같이 써야 한다 — 따로 반올림하면 필드에 보이는 값과 다이얼을 열었을 때 값이 어긋난다.
 *
 * @param {string} value "HH:mm"
 * @param {number} step 분 단위(예: 10)
 * @returns {string} "HH:mm", 항상 step 의 배수인 분을 가진다
 */
export function roundTimeToStep(value, step) {
  const [hStr, mStr] = value.split(':')
  const totalMinutes = parseInt(hStr, 10) * 60 + parseInt(mStr, 10)
  const rounded = Math.round(totalMinutes / step) * step
  const wrapped = ((rounded % 1440) + 1440) % 1440
  const hh = String(Math.floor(wrapped / 60)).padStart(2, '0')
  const mm = String(wrapped % 60).padStart(2, '0')
  return `${hh}:${mm}`
}
