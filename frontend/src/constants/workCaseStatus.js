/**
 * 근무(work_case) 상태 단일 소스 — 8단계 enum ↔ 화면 표시어·뱃지색 매핑.
 *
 * 도메인 규칙(docs/rules/domain.md · frontend.md): 상태 문자열을 컴포넌트에 하드코딩하지 않고
 * 반드시 이 파일의 매핑·헬퍼만 사용한다(상태 표기의 단일 원본).
 *
 * 상태 전이(v1.0 확정):
 *   DRAFT(수락 전) → ACCEPTED(수락·계약) → READY(시작 대기)
 *   → IN_PROGRESS(근무중) → COMPLETED(근무완료)
 *   확정 계열에서 NO_SHOW(미출근) · DRAFT에서 CANCELED(취소).
 * 초대 발급·대기 상태는 work_case가 아니라 work_invitations가 담당한다.
 *
 * CHECK_OUT_MISSING(퇴근 확인 필요)은 성공 출근 뒤 성공 퇴근이 없는 근무이며 NO_SHOW와
 * 상호 배타적이다(REQUIREMENTS ATT-006 · WORK-007). 판정 시점·해소·정산 흐름은
 * DEC-OPEN-CHECK-OUT-MISSING-FLOW 미결이라 이 파일은 표기만 담당한다.
 *
 * - `label`: 화면 표기 한글 문구.
 * - `color`: base.css 색 변수 문자열(그대로 style 바인딩). 아이콘 매핑은 StatusChip.vue.
 */
export const WORK_CASE_STATUS = {
  DRAFT: { label: '수락 전', color: 'var(--color-text-sub)' },
  ACCEPTED: { label: '계약완료', color: 'var(--color-owner)' },
  READY: { label: '근무예정', color: 'var(--color-owner)' },
  IN_PROGRESS: { label: '근무중', color: 'var(--color-primary)' },
  CHECK_OUT_MISSING: { label: '퇴근 확인 필요', color: 'var(--color-warning)' },
  COMPLETED: { label: '근무완료', color: 'var(--color-success)' },
  NO_SHOW: { label: '노쇼', color: 'var(--color-danger)' },
  CANCELED: { label: '취소', color: 'var(--color-text-sub)' }
}

/** 상태 → 표기 라벨(없으면 원문 반환). */
export function workCaseStatusLabel(status) {
  return WORK_CASE_STATUS[status]?.label ?? status
}

/** 상태 → 뱃지 색 변수(없으면 보조 텍스트색). */
export function workCaseStatusColor(status) {
  return WORK_CASE_STATUS[status]?.color ?? 'var(--color-text-sub)'
}

/** 수정·삭제 가능 여부에 사용하는 DRAFT 판별이다(서버도 DRAFT 만 허용한다). */
export function isDraft(status) {
  return status === 'DRAFT'
}

/**
 * '지각' 파생 표시 — READY인데 시작 시각이 지났고 아직 체크인 전인 구간의 화면 문구다.
 * work_cases.status 8종에 없는 값이라 WORK_CASE_STATUS에 넣지 않는다(필터·요약이 그
 * 8종만 순회하므로 섞으면 존재하지 않는 status로 필터·집계를 보내게 된다). StatusChip이
 * status='LATE'를 받으면 이 라벨을 특별 처리한다.
 */
export const DERIVED_LATE_STATUS = { label: '지각', color: 'var(--color-late)' }

/**
 * 화면 표시용 파생 상태를 계산한다.
 *
 * 서버는 시작+1시간 경계까지 NO_SHOW로 정리하지 않고 READY를 유지한다(API_SPEC 5C-1·5C-2).
 * 그 구간 동안 체크인 전이면 실제로는 지각인데도 화면은 '근무예정'으로 보여 사용자가 아직
 * 여유가 있다고 오인할 수 있다. 저장 status는 그대로 두고 표시만 'LATE'로 바꾼다.
 *
 * @param {object} workCase status, startsAt, attendance.checkedInAt 를 가진 근무 항목
 * @param {Date} now 비교 기준 시각(테스트에서 고정할 수 있게 주입한다)
 */
export function displayWorkCaseStatus(workCase, now = new Date()) {
  if (workCase?.status !== 'READY' || workCase?.attendance?.checkedInAt) {
    return workCase?.status
  }
  const startsAt = new Date(workCase?.startsAt)
  if (Number.isNaN(startsAt.getTime())) return workCase.status
  return now.getTime() > startsAt.getTime() ? 'LATE' : workCase.status
}

/**
 * 초대 Link 를 새로 발급할 수 있는 근무인지 판별한다.
 *
 * 서버(InvitationIssueServiceImpl)는 DRAFT 여부만이 아니라 **세 조건을 모두** 본다.
 *   DRAFT · 아직 매칭된 알바생 없음 · 근무 시작 시각 전
 * 하나라도 어긋나면 409 WORK_CASE_LOCKED 다. 승인 응답(WorkCaseListItemResponse ·
 * WorkCaseDetailResponse)에 capability 필드가 없으므로 같은 세 조건을 여기서 재현한다.
 * DRAFT 만 보고 버튼을 노출하면 시작 시각이 지난 근무에서 항상 실패하는 버튼이 보인다.
 *
 * 최종 권한은 서버에 있다. 이 판별은 실패할 동작을 미리 감추기 위한 것이다.
 *
 * @param {object} workCase status, worker, startsAt 를 가진 근무 항목
 * @param {Date} now 시작 시각 비교 기준(테스트에서 고정할 수 있게 주입한다)
 */
export function canIssueInvitation(workCase, now = new Date()) {
  if (!isDraft(workCase?.status) || workCase?.worker) return false
  const startsAt = new Date(workCase?.startsAt)
  return !Number.isNaN(startsAt.getTime()) && startsAt.getTime() > now.getTime()
}

/**
 * 초대(work_invitations) 상태 표기 — ck_work_invitations_status 의 5개.
 *
 * 근무(work_case) 상태와 별개다. 근무가 DRAFT 여도 초대는 PENDING·REVOKED·EXPIRED 일 수 있고,
 * 사장이 조건을 수정하면 서버가 PENDING 초대를 REVOKED 로 철회한다.
 */
export const INVITATION_STATUS = {
  PENDING: { label: '수락 대기' },
  ACCEPTED: { label: '수락됨' },
  REJECTED: { label: '거절됨' },
  REVOKED: { label: '철회됨' },
  EXPIRED: { label: '만료됨' }
}

/** 초대 상태 → 표기 라벨(없으면 원문 반환). */
export function invitationStatusLabel(status) {
  return INVITATION_STATUS[status]?.label ?? status
}

/**
 * 지금 사용할 수 있는 초대인지 판별한다.
 *
 * 서버는 만료 시각을 근무 시작 시각으로 두고, 만료가 지나도 status 를 즉시 EXPIRED 로 바꾸지
 * 않는다(수락 시점에 판정). 그래서 PENDING 이어도 만료 시각이 지났으면 쓸 수 없는 링크다.
 */
export function isInvitationUsable(invitation, now = new Date()) {
  if (invitation?.status !== 'PENDING') return false
  const expiresAt = new Date(invitation?.expiresAt)
  return !Number.isNaN(expiresAt.getTime()) && expiresAt.getTime() > now.getTime()
}

/**
 * 근태관리 요약 카운트(7종). `key` = 서버 요약 응답 필드, `status` = 매핑 enum.
 * CANCELED는 운영 현황 요약에 집계하지 않는다.
 *
 * CHECK_OUT_MISSING은 NO_SHOW·COMPLETED와 상호 배타적인 별도 상태라(위 문서 참고)
 * 두 버킷 중 하나로 합산하지 않고 독립 카드로 노출한다(WorkCaseSummaryResponse에도
 * 별도 필드로 내려온다).
 */
export const WORK_CASE_SUMMARY = [
  { key: 'draft', status: 'DRAFT' },
  { key: 'accepted', status: 'ACCEPTED' },
  { key: 'ready', status: 'READY' },
  { key: 'inProgress', status: 'IN_PROGRESS' },
  { key: 'checkOutMissing', status: 'CHECK_OUT_MISSING' },
  { key: 'completed', status: 'COMPLETED' },
  { key: 'noShow', status: 'NO_SHOW' }
]

/** 요약 카운트 초기값(모든 버킷 0). */
export function emptyWorkCaseSummary() {
  return Object.fromEntries(WORK_CASE_SUMMARY.map((b) => [b.key, 0]))
}

/**
 * 근태관리 검색·필터 시트의 '유형' 선택지 — 전체 + 8단계 전부.
 *
 * WORK_CASE_STATUS 에서 파생시킨다(라벨을 다시 적으면 단일 원본이 깨진다). 나열 순서는
 * 그 객체의 키 순서 = 상태 전이 순서다.
 * 요약 카드(WORK_CASE_SUMMARY)와 달리 CANCELED 도 넣는다 — 요약은 운영 현황 집계라
 * 취소를 세지 않지만, 필터는 취소된 근무를 찾아보는 용도가 있다.
 */
export const WORK_CASE_STATUS_FILTER = [
  { value: 'ALL', label: '전체' },
  ...Object.entries(WORK_CASE_STATUS).map(([status, { label }]) => ({ value: status, label }))
]
