/**
 * 금액·날짜 표시 포맷 유틸.
 * 금액은 KRW 정수 기준(소수점 없음). 화면 표기는 여기 함수만 사용한다.
 */

/** 1250000 → "1,250,000원" */
export function formatKRW(amount) {
  const n = Number(amount) || 0
  return `${n.toLocaleString('ko-KR')}원`
}

/** 거래 방향에 따라 부호를 붙인 금액. CREDIT(+) / DEBIT(-) */
export function formatSignedKRW(amount, direction) {
  const n = Math.abs(Number(amount) || 0)
  const sign = direction === 'DEBIT' ? '-' : '+'
  return `${sign}${formatKRW(n)}`
}

/** ISO-8601 문자열 → "MM.DD HH:mm" */
export function formatDateTime(iso) {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mi = String(d.getMinutes()).padStart(2, '0')
  return `${mm}.${dd} ${hh}:${mi}`
}

/** "2026-07-22" 또는 ISO → "2026.07.22" */
export function formatDate(value) {
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return ''
  const yyyy = d.getFullYear()
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${yyyy}.${mm}.${dd}`
}

/**
 * 시간 표기 → "HH:mm".
 * "09:00", "09:00:00", ISO 문자열 모두 허용.
 */
export function formatTime(value) {
  if (typeof value === 'string' && /^\d{2}:\d{2}/.test(value)) return value.slice(0, 5)
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return ''
  const hh = String(d.getHours()).padStart(2, '0')
  const mi = String(d.getMinutes()).padStart(2, '0')
  return `${hh}:${mi}`
}

/** 시작·종료 시간 → "09:00 ~ 18:00" */
export function formatTimeRange(start, end) {
  const s = formatTime(start)
  const e = formatTime(end)
  if (!s && !e) return ''
  return `${s} ~ ${e}`
}

/**
 * API 의 UTC Instant → 근무지 기준(Asia/Seoul) 벽시계 "HH:mm".
 *
 * 근무 시각은 DB 에 Asia/Seoul 벽시계로 저장되고 서버가 경계에서 UTC Instant 로 바꿔 준다
 * (docs/agent/ARCHITECTURE_OVERVIEW.md). 위 formatTime 은 브라우저 로컬 TZ 로 읽으므로
 * 사용자의 기기 설정이 KST 가 아니면 근무 시각이 어긋난다. 근무 시각은 "그 사업장에서 몇 시"가
 * 유일한 의미이므로 표시·편집 모두 Asia/Seoul 로 고정한다.
 *
 * 로컬 TZ 로 읽는 formatTime 은 "09:00" 같은 벽시계 문자열을 그대로 받는 다른 화면들이
 * 쓰고 있어 그대로 둔다.
 */
const SEOUL_WALL_CLOCK_TIME = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Seoul',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23'
})

export function formatSeoulTime(value) {
  if (value == null || value === '') return ''
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return ''
  return SEOUL_WALL_CLOCK_TIME.format(d)
}

/** UTC Instant → 근무지 기준(Asia/Seoul) 날짜 키 "2026-07-22". formatSeoulTime 과 같은 이유로
 * 브라우저 로컬 TZ 대신 Asia/Seoul 로 고정한다. */
const SEOUL_WALL_CLOCK_DATE_KEY = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit'
})

export function formatSeoulDateKey(value) {
  if (value == null || value === '') return ''
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return ''
  return SEOUL_WALL_CLOCK_DATE_KEY.format(d)
}

/**
 * UTC Instant 시작·종료 → 근무지 기준 "09:00 ~ 18:00".
 *
 * 자정을 넘기는 근무는 종료 앞에 '익일'을 붙여 "23:00 ~ 익일 01:00" 로 보여준다
 * (SPEC-413-01). 시각만 보여주면 23:00~01:00 이 22시간 근무인지 2시간 근무인지 구분되지
 * 않는다. 판정은 서울 날짜 키로 한다 — 브라우저 로컬 TZ 로 비교하면 시차가 있는 곳에서
 * 같은 날 근무에 '익일'이 붙는다.
 *
 * 근무 시간을 보여주는 화면이 모두 이 함수를 거치므로 표기 규칙은 여기 한 곳에만 둔다.
 */
export function formatSeoulTimeRange(start, end) {
  const s = formatSeoulTime(start)
  const e = formatSeoulTime(end)
  if (!s && !e) return ''

  const startKey = formatSeoulDateKey(start)
  const endKey = formatSeoulDateKey(end)
  const overnight = Boolean(startKey) && Boolean(endKey) && endKey !== startKey
  return `${s} ~ ${overnight ? '익일 ' : ''}${e}`
}

/** UTC Instant → 근무지 기준(Asia/Seoul) "2026.08.01 09:00" */
const SEOUL_WALL_CLOCK_DATE_TIME = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23'
})

export function formatSeoulDateTime(value) {
  if (value == null || value === '') return ''
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return ''
  // ko-KR 은 "2026. 08. 01. 09:00" 처럼 마침표와 공백을 섞어 준다. 표기를 통일한다.
  return SEOUL_WALL_CLOCK_DATE_TIME.format(d)
    .replace(/\.\s*/g, '.')
    .replace(/\.(\d{2}:)/, ' $1')
    .replace(/\.$/, '')
}

/** 숫자 외 문자를 모두 제거한다(계좌번호·금액 등 숫자 전용 입력의 붙여넣기/IME 대비). */
export function onlyDigits(value) {
  return String(value).replace(/\D/g, '')
}

/** 입력 중인 사업자등록번호에 하이픈을 자동으로 채운다. "1234567890" → "123-45-67890" */
export function formatBusinessNumberInput(value) {
  const digits = String(value).replace(/\D/g, '').slice(0, 10)
  const p1 = digits.slice(0, 3)
  const p2 = digits.slice(3, 5)
  const p3 = digits.slice(5, 10)
  return [p1, p2, p3].filter(Boolean).join('-')
}

/**
 * 입력 중인 전화번호에 하이픈을 자동으로 채운다.
 * 서울(02)은 2자리, 그 외(지역번호·휴대폰)는 3자리 국번 기준으로 구간을 나눈다.
 */
export function formatPhoneInput(value) {
  const digits = String(value).replace(/\D/g, '').slice(0, 11)

  if (digits.startsWith('02')) {
    if (digits.length < 3) return digits
    if (digits.length <= 6) return `${digits.slice(0, 2)}-${digits.slice(2)}`
    if (digits.length <= 9) return `${digits.slice(0, 2)}-${digits.slice(2, 5)}-${digits.slice(5)}`
    return `${digits.slice(0, 2)}-${digits.slice(2, 6)}-${digits.slice(6, 10)}`
  }

  if (digits.length < 4) return digits
  if (digits.length <= 7) return `${digits.slice(0, 3)}-${digits.slice(3)}`
  if (digits.length <= 10) return `${digits.slice(0, 3)}-${digits.slice(3, 6)}-${digits.slice(6)}`
  return `${digits.slice(0, 3)}-${digits.slice(3, 7)}-${digits.slice(7, 11)}`
}

// 편집·이동에 쓰는 제어 키 — 숫자 전용 입력에서도 항상 허용한다.
const DIGIT_INPUT_CONTROL_KEYS = [
  'Backspace',
  'Delete',
  'ArrowLeft',
  'ArrowRight',
  'ArrowUp',
  'ArrowDown',
  'Tab',
  'Home',
  'End'
]

/**
 * 사업자등록번호·전화번호처럼 숫자만 입력받는 필드에서 숫자 외 키 입력을 막는다.
 * @keydown 에 그대로 연결한다: <AppField @keydown="blockNonDigitKeydown" ... />
 */
export function blockNonDigitKeydown(e) {
  if (e.ctrlKey || e.metaKey || e.altKey) return
  if (DIGIT_INPUT_CONTROL_KEYS.includes(e.key)) return
  if (!/^\d$/.test(e.key)) e.preventDefault()
}

/** 분(minutes) → "7시간 30분" / "45분" */
export function formatDuration(minutes) {
  const m = Number(minutes) || 0
  const h = Math.floor(m / 60)
  const rest = m % 60
  if (h && rest) return `${h}시간 ${rest}분`
  if (h) return `${h}시간`
  return `${rest}분`
}
