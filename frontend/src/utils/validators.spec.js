/**
 * 입력 정규화·검증 단위 테스트 — 승인 계약의 경계값을 고정한다.
 * 프런트와 백엔드가 같은 경계를 쓰지 않으면 화면에서 통과한 값이 서버에서 거부된다.
 */
import { describe, expect, it } from 'vitest'

import {
  bankAccountRule,
  isEmail,
  isPhone,
  isWalletAmount,
  loginIdRule,
  NAME_MAX_LENGTH,
  nameRule,
  normalizeEmail,
  normalizeBankAccountNo,
  normalizeLoginId,
  normalizeName,
  normalizePhone,
  PASSWORD_MAX_BYTES,
  PASSWORD_MAX_LENGTH,
  PASSWORD_MIN_LENGTH,
  passwordRule,
  WALLET_AMOUNT_MAX,
  WORK_DURATION_MAX_MINUTES,
  workPeriodRule
} from '@/utils/validators'

const repeat = (char, count) => char.repeat(count)

describe('정규화', () => {
  it('아이디는 앞뒤 공백을 없애고 소문자로 만든다', () => {
    expect(normalizeLoginId('  Tester01  ')).toBe('tester01')
  })

  it('이메일은 앞뒤 공백을 없애고 소문자로 만든다', () => {
    expect(normalizeEmail(' User@Example.COM ')).toBe('user@example.com')
  })

  it('이름은 공백만 정리하고 대소문자는 보존한다', () => {
    expect(normalizeName('  Kim SaJang  ')).toBe('Kim SaJang')
  })

  it('전화번호는 하이픈과 공백을 제거해 숫자만 남긴다', () => {
    expect(normalizePhone('010-1234-5678')).toBe('01012345678')
    expect(normalizePhone('02 123 4567')).toBe('021234567')
  })

  it('계좌번호는 공백과 하이픈만 제거한다', () => {
    expect(normalizeBankAccountNo('170-0000 00001')).toBe('170000000001')
    expect(normalizeBankAccountNo('170A00000001')).toBe('170A00000001')
  })

  it('null·undefined 는 빈 문자열로 다룬다', () => {
    expect(normalizeLoginId(null)).toBe('')
    expect(normalizePhone(undefined)).toBe('')
  })
})

describe('지갑 계좌·금액 검증', () => {
  it('정규화한 계좌번호가 숫자 10~14자리일 때만 통과한다', () => {
    expect(bankAccountRule('170-0000-0001').valid).toBe(true)
    expect(bankAccountRule('123456789').valid).toBe(false)
    expect(bankAccountRule('123456789012345').valid).toBe(false)
    expect(bankAccountRule('170A00000001').valid).toBe(false)
  })

  it('지갑 금액은 1원부터 1억원까지의 정수만 통과한다', () => {
    expect(isWalletAmount(1).valid).toBe(true)
    expect(isWalletAmount(WALLET_AMOUNT_MAX).valid).toBe(true)
    expect(isWalletAmount(0).valid).toBe(false)
    expect(isWalletAmount(WALLET_AMOUNT_MAX + 1).valid).toBe(false)
    expect(isWalletAmount(1.5).valid).toBe(false)
  })
})

describe('passwordRule — 문자 수 경계', () => {
  it(`${PASSWORD_MIN_LENGTH}자 미만은 거부한다`, () => {
    expect(passwordRule(repeat('a', PASSWORD_MIN_LENGTH - 1)).valid).toBe(false)
  })

  it(`${PASSWORD_MIN_LENGTH}자는 통과한다`, () => {
    expect(passwordRule(repeat('a', PASSWORD_MIN_LENGTH)).valid).toBe(true)
  })

  it(`${PASSWORD_MAX_LENGTH}자는 통과한다`, () => {
    expect(passwordRule(repeat('a', PASSWORD_MAX_LENGTH)).valid).toBe(true)
  })

  it(`${PASSWORD_MAX_LENGTH + 1}자는 거부한다`, () => {
    expect(passwordRule(repeat('a', PASSWORD_MAX_LENGTH + 1)).valid).toBe(false)
  })
})

describe('passwordRule — UTF-8 byte 경계', () => {
  // 한글 1자는 UTF-8 로 3 byte 다. BCrypt 가 72 byte 까지만 사용하므로 문자 수만으로는 막지 못한다.
  const koreanAtLimit = repeat('가', PASSWORD_MAX_BYTES / 3) // 24자 = 72 byte
  const koreanOverLimit = repeat('가', PASSWORD_MAX_BYTES / 3 + 1) // 25자 = 75 byte

  it(`정확히 ${PASSWORD_MAX_BYTES} byte 는 통과한다`, () => {
    expect(new TextEncoder().encode(koreanAtLimit).length).toBe(PASSWORD_MAX_BYTES)
    expect(passwordRule(koreanAtLimit).valid).toBe(true)
  })

  it(`${PASSWORD_MAX_BYTES} byte 초과는 문자 수가 상한 이내여도 거부한다`, () => {
    expect(koreanOverLimit.length).toBeLessThanOrEqual(PASSWORD_MAX_LENGTH)
    expect(new TextEncoder().encode(koreanOverLimit).length).toBeGreaterThan(PASSWORD_MAX_BYTES)
    expect(passwordRule(koreanOverLimit).valid).toBe(false)
  })
})

describe('passwordRule — 문자 종류', () => {
  it('영문만으로도 통과한다(조합 규칙을 강제하지 않는다)', () => {
    expect(passwordRule('abcdefgh').valid).toBe(true)
  })

  it('숫자만으로도 통과한다', () => {
    expect(passwordRule('12345678').valid).toBe(true)
  })

  it('빈 값은 거부한다', () => {
    expect(passwordRule('').valid).toBe(false)
  })
})

describe('loginIdRule', () => {
  it('대문자를 섞어 입력해도 정규화 후 통과한다', () => {
    expect(loginIdRule('Tester01').valid).toBe(true)
  })

  it('앞뒤 공백이 있어도 통과한다', () => {
    expect(loginIdRule('  tester01  ').valid).toBe(true)
  })

  it('3자는 거부하고 4자는 통과한다', () => {
    expect(loginIdRule('abc').valid).toBe(false)
    expect(loginIdRule('abcd').valid).toBe(true)
  })

  it('20자는 통과하고 21자는 거부한다', () => {
    expect(loginIdRule(repeat('a', 20)).valid).toBe(true)
    expect(loginIdRule(repeat('a', 21)).valid).toBe(false)
  })

  it('영문·숫자 외 문자는 거부한다', () => {
    expect(loginIdRule('tester_01').valid).toBe(false)
  })
})

describe('nameRule', () => {
  it('공백만 입력하면 거부한다', () => {
    expect(nameRule('   ').valid).toBe(false)
  })

  it('앞뒤 공백을 정리한 뒤 판정한다', () => {
    expect(nameRule('  김사장  ').valid).toBe(true)
  })

  it(`${NAME_MAX_LENGTH}자는 통과하고 ${NAME_MAX_LENGTH + 1}자는 거부한다`, () => {
    expect(nameRule(repeat('가', NAME_MAX_LENGTH)).valid).toBe(true)
    expect(nameRule(repeat('가', NAME_MAX_LENGTH + 1)).valid).toBe(false)
  })

  it('공백을 제거하면 상한 이내인 값은 통과한다', () => {
    expect(nameRule(`  ${repeat('가', NAME_MAX_LENGTH)}  `).valid).toBe(true)
  })
})

describe('isEmail', () => {
  it('앞뒤 공백과 대문자를 정규화한 뒤 판정한다', () => {
    expect(isEmail(' User@Example.COM ').valid).toBe(true)
  })

  it('형식이 아니면 거부한다', () => {
    expect(isEmail('user@').valid).toBe(false)
  })
})

describe('isPhone', () => {
  it('하이픈이 있어도 정규화 후 통과한다', () => {
    expect(isPhone('010-1234-5678').valid).toBe(true)
  })

  it('0 으로 시작하는 9~11자리를 허용한다', () => {
    expect(isPhone('021234567').valid).toBe(true) // 9자리
    expect(isPhone('01012345678').valid).toBe(true) // 11자리
  })

  it('8자리와 12자리는 거부한다', () => {
    expect(isPhone('02123456').valid).toBe(false)
    expect(isPhone('010123456789').valid).toBe(false)
  })

  it('0 으로 시작하지 않으면 거부한다', () => {
    expect(isPhone('11012345678').valid).toBe(false)
  })

  it('선택 항목이라 빈 값은 통과하고, required 면 거부한다', () => {
    expect(isPhone('').valid).toBe(true)
    expect(isPhone('', { required: true }).valid).toBe(false)
  })
})

/**
 * 근무 시간대 규칙(SPEC-413-01).
 *
 * 자정 넘김을 허용하면 "종료가 시작보다 이르다"는 오타 방어선이 사라진다. 그 자리를 길이
 * 상한이 대신하므로, 상한의 **경계 양쪽**을 함께 고정하지 않으면 규칙이 있으나 마나다.
 * 이 값은 서버 WorkCaseTimes.MAX_WORK_DURATION 과 같아야 한다 — 어긋나면 화면을 통과한
 * 입력이 서버에서 거부된다.
 */
describe('workPeriodRule', () => {
  it('상수가 서버와 같은 16시간을 가리킨다', () => {
    expect(WORK_DURATION_MAX_MINUTES).toBe(16 * 60)
  })

  it('같은 날 끝나는 근무는 그대로 통과한다', () => {
    expect(workPeriodRule('09:00', '18:00').valid).toBe(true)
  })

  it('자정을 넘기는 근무를 통과시킨다', () => {
    expect(workPeriodRule('23:00', '01:00').valid).toBe(true)
    expect(workPeriodRule('22:30', '06:00').valid).toBe(true)
  })

  it('정확히 16시간은 통과하고 1분만 넘어도 거부한다', () => {
    expect(workPeriodRule('20:00', '12:00').valid).toBe(true) // 16시간
    expect(workPeriodRule('20:00', '12:01').valid).toBe(false) // 16시간 1분
  })

  it('시작과 종료가 같으면 0분이 아니라 24시간이라 거부한다', () => {
    // 0분으로 접으면 09:00~09:00 오타가 저장 가능한 값이 된다.
    const result = workPeriodRule('09:00', '09:00')
    expect(result.valid).toBe(false)
    expect(result.message).toContain('16시간')
  })

  it('종료시간이 비면 필수 항목으로 알린다', () => {
    const result = workPeriodRule('09:00', '')
    expect(result.valid).toBe(false)
    expect(result.message).toContain('종료시간')
  })

  it('시작시간이 아직 비었으면 이 규칙은 판단하지 않는다', () => {
    // 시작시간의 필수 검증은 그 필드가 따로 한다. 여기서 겹쳐 알리면 오류가 두 번 뜬다.
    expect(workPeriodRule('', '18:00').valid).toBe(true)
  })

  /**
   * 시각으로 읽히지 않는 종료시간을 통과시키면 길이 상한이 통째로 건너뛰어진다. 시·분
   * 범위를 보지 않던 예전 파서는 '99:99' 를 6039분으로 접어 상한 검사를 통과시켰다.
   */
  it('종료시간이 시각으로 읽히지 않으면 거부한다', () => {
    expect(workPeriodRule('09:00', '99:99').valid).toBe(false)
    expect(workPeriodRule('09:00', '25:00').valid).toBe(false)
    expect(workPeriodRule('09:00', '오후 6시').valid).toBe(false)
  })

  it('한 자리 시각과 초를 포함한 표기도 같은 규칙으로 읽는다', () => {
    // 화면은 input[type=time] 이라 "HH:mm" 만 오지만, 서버 값을 그대로 넣는 경로가
    // 생기면 "09:00:00" 이 온다. earning.js 와 같은 파서를 쓰므로 해석이 갈리지 않는다.
    expect(workPeriodRule('9:00', '18:00').valid).toBe(true)
    expect(workPeriodRule('09:00:00', '18:00:00').valid).toBe(true)
    expect(workPeriodRule('9:00', '9:00').valid).toBe(false) // 24시간
  })
})
