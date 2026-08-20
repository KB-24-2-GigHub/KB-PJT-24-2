/**
 * 폼 입력 정규화·검증 유틸.
 *
 * 승인 계약(docs/specs/API_SPEC.md '인증·회원' 절, REQUIREMENTS AUTH-002·AUTH-008)은
 * 정규화를 먼저 적용한 뒤 검증하도록 정한다. 가용성 조회·가입·로그인이 같은 규칙을 쓰지
 * 않으면 사전 확인을 통과한 값이 최종 요청에서 거부된다.
 * 서버가 최종 검증하지만 폼 UX 를 위해 프론트에서도 같은 경계를 즉시 안내한다.
 *
 * 각 검증 함수는 `{ valid: boolean, message: string }` 를 반환한다(통과 시 message '').
 * 회원가입·사업장 등록·비밀번호 변경 등 폼 화면에서 공통으로 사용한다.
 */
import { onlyDigits, parseWallClockMinutes } from '@/utils/format'

const ok = { valid: true, message: '' }
const fail = (message) => ({ valid: false, message })

// 승인 계약의 비밀번호 경계. BCrypt 는 입력을 72 byte 까지만 사용하므로 문자 수 상한만으로는
// 멀티바이트 입력을 막지 못한다. 두 경계를 함께 적용하고 초과분을 잘라내지 않는다.
export const PASSWORD_MIN_LENGTH = 8
export const PASSWORD_MAX_LENGTH = 64
export const PASSWORD_MAX_BYTES = 72

/** 승인 계약의 이름 상한. 정규화(trim) 후 길이를 기준으로 한다. */
export const NAME_MAX_LENGTH = 100

/** 승인 지갑 계약의 단일 요청 금액 상한. */
export const WALLET_AMOUNT_MAX = 100_000_000

/** 아이디 정규화 — 앞뒤 공백 제거 후 소문자. 저장·비교·전송 모두 이 형태를 쓴다. */
export function normalizeLoginId(value) {
  return String(value ?? '')
    .trim()
    .toLowerCase()
}

/** 이메일 정규화 — 앞뒤 공백 제거 후 소문자. */
export function normalizeEmail(value) {
  return String(value ?? '')
    .trim()
    .toLowerCase()
}

/** 이름 정규화 — 앞뒤 공백만 제거한다(대소문자는 입력 그대로 보존). */
export function normalizeName(value) {
  return String(value ?? '').trim()
}

/** 전화번호 정규화 — 공백·하이픈 등 구분 문자를 제거해 숫자만 남긴다. 표시 형식은 화면이 만든다. */
export function normalizePhone(value) {
  return value == null ? '' : onlyDigits(value)
}

/** 계좌번호 정규화 — 서버와 동일하게 공백과 하이픈만 제거한다. */
export function normalizeBankAccountNo(value) {
  return String(value ?? '').replace(/[\s-]/g, '')
}

/** UTF-8 기준 byte 길이. 한글 1자는 3 byte 이므로 문자 수와 다르다. */
function utf8ByteLength(value) {
  return new TextEncoder().encode(value).length
}

/** 필수값 */
export function isRequired(value, label = '필수 항목') {
  const v = typeof value === 'string' ? value.trim() : value
  if (v === '' || v === null || v === undefined) return fail(`${label}을(를) 입력해주세요.`)
  return ok
}

/** 이메일 형식. 정규화한 값으로 검사한다. */
export function isEmail(value) {
  const normalized = normalizeEmail(value)
  if (!normalized) return fail('이메일을 입력해주세요.')
  const re = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
  return re.test(normalized) ? ok : fail('올바른 이메일 형식이 아닙니다.')
}

/**
 * 비밀번호 규칙(승인 계약): 8~64자이면서 UTF-8 기준 72 byte 이하.
 * 72 byte 를 넘는 입력은 잘라내지 않고 거부한다. 잘라내면 서로 다른 비밀번호가 같은
 * 해시가 되어 아무 값으로나 로그인할 수 있다.
 * 문자 종류 조합은 강제하지 않으며, 비밀번호에는 trim·대소문자 변환을 적용하지 않는다.
 */
export function passwordRule(value) {
  if (!value) return fail('비밀번호를 입력해주세요.')
  if (value.length < PASSWORD_MIN_LENGTH || value.length > PASSWORD_MAX_LENGTH) {
    return fail(`비밀번호는 ${PASSWORD_MIN_LENGTH}~${PASSWORD_MAX_LENGTH}자여야 합니다.`)
  }
  if (utf8ByteLength(value) > PASSWORD_MAX_BYTES) {
    return fail(
      `비밀번호가 너무 깁니다. 한글·이모지를 줄여 ${PASSWORD_MAX_BYTES}byte 이하로 입력해주세요.`
    )
  }
  return ok
}

/** 비밀번호 확인 일치 */
export function passwordsMatch(password, confirm) {
  if (!confirm) return fail('비밀번호 확인을 입력해주세요.')
  return password === confirm ? ok : fail('비밀번호가 일치하지 않습니다.')
}

/**
 * 아이디: 4~20자 영문·숫자. 정규화한 값으로 검사한다.
 * 승인 명세는 정규화 규칙만 고정했고 길이·문자 범위는 지정하지 않아 기존 화면 기준을 유지한다.
 */
export function loginIdRule(value) {
  const normalized = normalizeLoginId(value)
  if (!normalized) return fail('아이디를 입력해주세요.')
  return /^[a-z0-9]{4,20}$/.test(normalized) ? ok : fail('아이디는 4~20자 영문·숫자입니다.')
}

/** 이름: 정규화(trim) 후 1~100자. 대소문자와 내부 공백은 입력 그대로 보존한다. */
export function nameRule(value) {
  const normalized = normalizeName(value)
  if (!normalized) return fail('이름을 입력해주세요.')
  if (normalized.length > NAME_MAX_LENGTH) {
    return fail(`이름은 ${NAME_MAX_LENGTH}자 이내로 입력해주세요.`)
  }
  return ok
}

/** 사업자등록번호: 10자리 숫자(하이픈 허용) */
export function isBusinessNumber(value) {
  if (!value) return fail('사업자등록번호를 입력해주세요.')
  const digits = String(value).replace(/-/g, '')
  return /^\d{10}$/.test(digits) ? ok : fail('사업자등록번호는 숫자 10자리입니다.')
}

/**
 * 전화번호(선택 항목): 값이 있으면 형식 검사.
 * 승인 계약은 정규화한 숫자가 `0`으로 시작하는 9~11자리일 것을 요구한다.
 */
export function isPhone(value, { required = false } = {}) {
  const digits = normalizePhone(value)
  if (!digits) return required ? fail('전화번호를 입력해주세요.') : ok
  return /^0\d{8,10}$/.test(digits) ? ok : fail('올바른 전화번호 형식이 아닙니다.')
}

/** 금액(양의 정수) */
export function isPositiveAmount(value) {
  const n = Number(value)
  if (!Number.isFinite(n) || n <= 0) return fail('금액을 올바르게 입력해주세요.')
  if (!Number.isInteger(n)) return fail('금액은 원 단위 정수로 입력해주세요.')
  return ok
}

/** Mock 은행계좌 번호: 정규화 후 숫자 10~14자리. */
export function bankAccountRule(value) {
  const normalized = normalizeBankAccountNo(value)
  if (!normalized) return fail('계좌번호를 입력해주세요.')
  return /^\d{10,14}$/.test(normalized)
    ? ok
    : fail('계좌번호는 공백·하이픈을 제외한 숫자 10~14자리여야 합니다.')
}

const MINUTES_PER_DAY = 24 * 60

/**
 * 근무 한 건의 최대 길이(분). 서버 WorkCaseTimes.MAX_WORK_DURATION 과 같은 값이다.
 * 두 곳이 어긋나면 프론트를 통과한 입력이 서버에서 거절된다.
 */
export const WORK_DURATION_MAX_MINUTES = 16 * 60

/**
 * 근무 시간대 검증 — 자정 넘김을 허용하되 길이 상한을 둔다(SPEC-413-01).
 *
 * 종료가 시작보다 뒤가 아니면 **다음 날**로 본다. 그래서 `23:00~01:00` 은 2시간이고,
 * `09:00~09:00` 은 0분이 아니라 24시간이라 상한에서 걸린다 — 0분으로 접으면 그 오타가
 * 저장 가능한 값이 되어 버린다.
 *
 * 시작·종료 순서로는 더 이상 오타를 걸러 낼 수 없으므로 길이 상한이 그 자리를 대신한다.
 *
 * @param {string} startTime "HH:mm"
 * @param {string} endTime "HH:mm"
 */
export function workPeriodRule(startTime, endTime) {
  const required = isRequired(endTime, '종료시간')
  if (!required.valid) return required

  const end = parseWallClockMinutes(endTime)
  // 비어 있지 않은데 시각으로 읽히지 않으면 길이를 잴 수 없다. 여기서 통과시키면 형식이
  // 어긋난 입력이 상한 검사를 통째로 건너뛴다.
  if (end === null) return fail('종료시간을 HH:mm 형식으로 입력해주세요.')

  const start = parseWallClockMinutes(startTime)
  // 시작시간이 아직 비었거나 형식이 아니면 이 규칙이 판단할 게 없다(그 필드가 따로 알린다).
  if (start === null) return ok

  const minutes = end > start ? end - start : end - start + MINUTES_PER_DAY
  return minutes <= WORK_DURATION_MAX_MINUTES
    ? ok
    : fail(`근무 시간은 최대 ${WORK_DURATION_MAX_MINUTES / 60}시간까지 등록할 수 있어요.`)
}

/**
 * 휴게시간 규칙 — 0 이상 정수이면서 근무 길이를 넘지 않아야 한다.
 *
 * 유급 휴게는 근무 길이와 같아도 되지만, 무급 휴게가 근무 전체와 같으면 차감 분모가
 * 0분이 되므로 등록할 수 없다. 새 정산 규칙과 같은 경계를 등록·수정 화면에서 먼저 본다.
 *
 * 길이는 workPeriodRule 과 같은 방식으로 앞으로 흐른 거리로 잰다. 자정을 넘기는 근무를
 * 단순 뺄셈으로 재면 음수가 되어 어떤 휴게든 통과한다.
 *
 * 등록·수정 두 화면이 같은 경계를 쓰도록 규칙을 여기 한 곳에 둔다.
 *
 * @param {string} startTime "HH:mm"
 * @param {string} endTime "HH:mm"
 * @param {number|string} breakMinutes 비우면 휴게 없음
 * @param {boolean} breakPaid 유급 휴게 여부
 */
export function breakMinutesRule(startTime, endTime, breakMinutes, breakPaid = false) {
  if (breakMinutes === '' || breakMinutes == null) return ok

  const minutes = Number(breakMinutes)
  if (!Number.isInteger(minutes) || minutes < 0) {
    return fail('휴게시간은 0 이상 분 단위로 입력해주세요.')
  }

  const start = parseWallClockMinutes(startTime)
  const end = parseWallClockMinutes(endTime)
  // 시각을 읽을 수 없으면 길이를 잴 수 없다. 그 필드들의 검증은 각자 따로 한다.
  if (start === null || end === null) return ok

  const workMinutes = end > start ? end - start : end - start + MINUTES_PER_DAY
  if (minutes > workMinutes) {
    return fail(`휴게시간은 근무 시간(${workMinutes}분)을 넘을 수 없어요.`)
  }
  if (!breakPaid && minutes === workMinutes) {
    return fail(`무급 휴게시간은 근무 시간(${workMinutes}분)보다 짧아야 해요.`)
  }
  return ok
}

/** 지갑 충전·출금 금액: 1원 이상 1억원 이하의 원 단위 정수. */
export function isWalletAmount(value) {
  const base = isPositiveAmount(value)
  if (!base.valid) return base
  return Number(value) <= WALLET_AMOUNT_MAX
    ? ok
    : fail(`금액은 ${WALLET_AMOUNT_MAX.toLocaleString('ko-KR')}원 이하여야 합니다.`)
}
