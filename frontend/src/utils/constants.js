/**
 * 화면 공통 상수 — 상태 라벨·색 토큰·코드 목록.
 *
 * 화면 표기 용어와 색을 여기서 단일 관리한다(도메인 규칙 docs/rules/domain.md 기준).
 * - `label`: 화면에 노출할 한글 문구(예: DB enum `SETTLED` → '정산완료').
 * - `color`: base.css 색 변수 문자열. 컴포넌트는 이 값을 그대로 style 에 바인딩한다.
 *
 * 아이콘 매핑(lucide)은 이 파일이 아니라 StatusChip.vue 가 담당한다(여기는 순수 데이터).
 *
 * 근무(work_case) 7단계 상태 매핑은 여기가 아니라 `@/constants/workCaseStatus` 단일 소스에 있다.
 */
import bankHana from '@/assets/images/banks/hana.png'
import bankKb from '@/assets/images/banks/kb.png'
import bankNh from '@/assets/images/banks/nh.png'
import bankShinhan from '@/assets/images/banks/shinhan.png'
import bankWoori from '@/assets/images/banks/woori.png'

/* ---- 정산·에스크로 상태 ---- */
export const SETTLE_STATUS = {
  NONE: { label: '정산대기', color: 'var(--color-text-sub)' },
  HOLD: { label: '예치중', color: 'var(--color-brand)' },
  SETTLED: { label: '정산완료', color: 'var(--color-success)' },
  REFUNDED: { label: '환불완료', color: 'var(--color-text-sub)' },

  // settlements.status 저장 값(ck_settlements_status). 위 네 값은 아직 실연동 전인 화면들이
  // 쓰는 표시 전용 값이라 그대로 두고, 서버가 실제로 내려주는 7종을 함께 매핑한다.
  // 매핑이 없으면 StatusChip 이 원문("WAITING")을 그대로 노출한다.
  WAITING: { label: '정산대기', color: 'var(--color-text-sub)' },
  SCHEDULED: { label: '정산예정', color: 'var(--color-brand)' },
  PROCESSING: { label: '정산중', color: 'var(--color-brand)' },
  COMPLETED: { label: '정산완료', color: 'var(--color-success)' },
  FAILED: { label: '정산실패', color: 'var(--color-danger)' },
  ON_HOLD: { label: '정산보류', color: 'var(--color-warning)' }
}

/**
 * 에스크로(escrows.status) 표기 — ck_escrows_status 의 5개.
 * 정산(settlements.status)과 다른 축이라 SETTLE_STATUS 와 섞지 않는다.
 */
export const ESCROW_STATUS = {
  UNFUNDED: { label: '미예치', color: 'var(--color-text-sub)' },
  HELD: { label: '예치중', color: 'var(--color-brand)' },
  RELEASED: { label: '지급완료', color: 'var(--color-success)' },
  REFUNDED: { label: '환불완료', color: 'var(--color-text-sub)' },
  ON_HOLD: { label: '보류', color: 'var(--color-warning)' }
}

/** 에스크로 상태 → 표기 라벨(없으면 원문 반환). */
export function escrowStatusLabel(status) {
  return ESCROW_STATUS[status]?.label ?? status
}

/* ---- 거래 상태 칩 ---- */
export const TX_STATUS = {
  DONE: { label: '완료', color: 'var(--color-text-sub)' },
  HOLD: { label: '예치중', color: 'var(--color-brand)' },
  SETTLED: { label: '정산완료', color: 'var(--color-success)' },
  REFUNDED: { label: '환불완료', color: 'var(--color-text-sub)' }
}

/* ---- 송금상세 필터(GET /api/wallet/transactions) 선택지 ----
 * 값은 백엔드 wallet_transactions.transaction_type 허용 목록과 동일하게 유지한다. */
export const TX_TYPE_FILTER = [
  { value: 'ALL', label: '전체' },
  { value: 'FUNDING', label: '충전' },
  { value: 'ESCROW_HOLD', label: '예치중' },
  { value: 'ESCROW_RELEASE', label: '지급완료' },
  { value: 'ESCROW_REFUND', label: '환불' },
  { value: 'WITHDRAWAL', label: '출금' },
  { value: 'WITHDRAWAL_REFUND', label: '출금환불' },
  { value: 'ADJUSTMENT', label: '조정' }
]
export const TX_SORT = [
  { value: 'LATEST', label: '최신순' },
  { value: 'OLDEST', label: '오래된순' },
  { value: 'AMOUNT_DESC', label: '금액 높은순' },
  { value: 'AMOUNT_ASC', label: '금액 낮은순' }
]

/* ---- 문서 유형·출처 ---- */
// 승인 계약(API_SPEC '문서')이 고정한 Enum. CONTRACT·HEALTH_CERT 별칭은 사용하지 않는다.
export const DOC_TYPE = {
  EMPLOYMENT_CONTRACT: { label: '근로계약서' },
  HEALTH_CERTIFICATE: { label: '보건증' }
}
export const DOC_SOURCE = {
  OWN: { label: '내 문서' },
  SHARED: { label: '공유받음' }
}
// 서버가 허용하는 문서 MIME 은 이 세 가지뿐이다(API_SPEC '파일 응답·접근 감사').
// 목록·상세는 fileExt 를 주지 않으므로 미리보기 형태는 mimeType 으로만 판정한다.
export const DOC_IMAGE_MIME_TYPES = ['image/jpeg', 'image/png']
export const DOC_PDF_MIME_TYPE = 'application/pdf'

/* ---- 보건증 공유 상태(서버 계산값) ---- */
export const DOC_SHARE_STATUS = {
  ACTIVE: { label: '공유중' },
  EXPIRED: { label: '기간 만료' },
  REVOKED: { label: '공유 취소됨' }
}

/* ---- 신뢰 뱃지(GET /api/users/me/badge) ----
 *
 * `role` 은 응답 `badgeType` 을 화면 역할로 옮기는 유일한 표이고, 여기 있는 타입만 그린다.
 * `definitionTitle`/`definitionDesc` 는 "무엇이 정상인가"를 설명하는 FE 소유 정의문이다 —
 * 서버 응답의 `criterionDesc`(누적·정상 건수와 다음 등급 조건을 안내하는 진행 설명문)와
 * 다른 문장이며 서로 대체하지 않는다. `criterionLabel` 은 승인 계약상 서버 값을 그대로
 * 쓰므로 여기 두지 않는다 — 화면이 지어낸 라벨이 응답을 덮어쓰는 경로를 만들지 않기 위함이다.
 */
export const BADGE_TYPE = {
  TRUST_WORKER: {
    role: 'worker',
    // 프로필 카드 타이틀("성실알바 Lv.N")·본문 라벨. criterionLabel(서버 값 "성실근로")과
    // 값이 같지만 별개 소유다 — 여기 문구를 바꿔도 API 계약은 그대로다.
    title: '성실알바',
    totalLabel: '근로',
    normalLabel: '성실근로',
    remainingLabel: '근무',
    definitionTitle: '👷 성실근로란?',
    definitionDesc: '지각·결근 없이 정상 출퇴근 완료'
  },
  TRUST_OWNER: {
    role: 'owner',
    title: '안심사장',
    totalLabel: '정산',
    normalLabel: '안심정산',
    remainingLabel: '정산',
    definitionTitle: '💵 안심정산이란?',
    definitionDesc: '임금 분쟁 없이 깔끔하게 완료된 정산 내역이에요.'
  }
}

/* ---- 알림 유형(GET /api/notifications notiType) ---- */
export const NOTI_TYPE = {
  WORK_CASE_CONFIRMED: { label: '근무 확정' },
  ESCROW_HELD: { label: '예치 완료' },
  SETTLED: { label: '정산 완료' },
  REFUNDED: { label: '노쇼 환불' },
  DOC_SHARED: { label: '보건증 공유' },
  WAGE_REPORTED: { label: '임금분쟁 신고' }
}

/* ---- QR 스캔 결과(POST /api/attendance/scans scanType) ---- */
export const SCAN_TYPE = {
  CHECK_IN: { label: '출근' },
  CHECK_OUT: { label: '퇴근' }
}

/**
 * 은행 목록(충전·출금 은행 선택).
 * `logo`: assets/images/banks/*.png 로고(없으면 BankSelect가 `chip` 색 점으로 대체 표시).
 *
 * SPEC 4.1.0 기준 승인된 canonical bankCode 20종(docs/specs/API_SPEC.md '지갑과 거래').
 * 화면 라벨과 API 전송값을 분리하고, `KB`나 `SHINHAN` 같은 별칭을 전송값으로 다시
 * 도입하지 않는다. `131`은 DGB대구은행과 화면 선택지를 구분하기 위한 iM뱅크 전용
 * 프로젝트 코드다.
 */
export const BANKS = [
  { code: '004', name: 'KB국민은행', logo: bankKb, chip: '#FFCC00' },
  { code: '088', name: '신한은행', logo: bankShinhan, chip: '#0046FF' },
  { code: '020', name: '우리은행', logo: bankWoori, chip: '#0067AC' },
  { code: '081', name: '하나은행', logo: bankHana, chip: '#008485' },
  { code: '011', name: 'NH농협은행', logo: bankNh, chip: '#19A94B' },
  { code: '003', name: '기업은행', logo: null, chip: '#004EA2' },
  { code: '090', name: '카카오뱅크', logo: null, chip: '#FEE500' },
  { code: '092', name: '토스뱅크', logo: null, chip: '#0064FF' },
  { code: '089', name: '케이뱅크', logo: null, chip: '#FF4D4D' },
  { code: '032', name: '부산은행', logo: null, chip: '#00519E' },
  { code: '031', name: 'DGB대구은행', logo: null, chip: '#0F4C9A' },
  { code: '131', name: 'iM뱅크', logo: null, chip: '#5B3EBB' },
  { code: '034', name: '광주은행', logo: null, chip: '#E4032E' },
  { code: '023', name: 'SC제일은행', logo: null, chip: '#003057' },
  { code: '027', name: '씨티은행', logo: null, chip: '#003882' },
  { code: '002', name: 'KDB산업은행', logo: null, chip: '#00478A' },
  { code: '007', name: '수협은행', logo: null, chip: '#0067AC' },
  { code: '045', name: '새마을금고', logo: null, chip: '#00954E' },
  { code: '048', name: '신협', logo: null, chip: '#0068B7' },
  { code: '071', name: '우체국', logo: null, chip: '#D0021B' }
]

export const BANKS_ALL = BANKS

/** 은행 코드 → 은행 객체 조회(전체 목록 기준) */
export function findBank(code) {
  return BANKS_ALL.find((b) => b.code === code) ?? null
}
