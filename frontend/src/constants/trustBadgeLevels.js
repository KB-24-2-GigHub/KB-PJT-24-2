/**
 * SPEC-178-06이 정한 뱃지 등급 문턱(누적 건수·정상 비율)이다. `docs/specs/API_SPEC.md`
 * '최신 뱃지'에 이미 공개 문서화된 값이라 비밀은 아니지만, 서버가 문턱을 바꾸면 이 표도
 * 함께 고쳐야 한다.
 *
 * `useTrustBadge.js`·`TrustBadge.vue`는 "문턱은 서버 소유, 화면은 몰라야 한다"는 원칙을
 * 지키지만, 레벨 설명 모달과 "다음 등급까지 필요한 비율" 안내 문구는 문턱 수치 자체를
 * 화면에 보여줘야 해서 예외로 여기 한 곳에만 하드코딩한다(#432에서 승인).
 */
export const TRUST_BADGE_LEVEL_THRESHOLDS = [
  { level: 1, thresholdCount: 10, thresholdPercent: 80 },
  { level: 2, thresholdCount: 20, thresholdPercent: 90 },
  { level: 3, thresholdCount: 30, thresholdPercent: 100 }
]

/** 주어진 등급(1~3)의 문턱. 정의되지 않은 등급(0 등)은 null이다. */
export function thresholdForLevel(level) {
  return TRUST_BADGE_LEVEL_THRESHOLDS.find((t) => t.level === level) ?? null
}
