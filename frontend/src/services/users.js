/**
 * 회원(내 정보·뱃지) API 서비스.
 *
 * 승인 계약: docs/specs/API_SPEC.md '내 프로필' 절, REQUIREMENTS AUTH-008, DEC-PROFILE-IMMUTABLE
 *   GET /api/users/me   PATCH /api/users/me   PATCH /api/users/me/password
 *   POST /api/users/me/withdrawal   GET /api/users/me/badge
 */
import http from '@/services/http'
import { USE_MOCK } from '@/services/mockFlag'
import { normalizePhone } from '@/utils/validators'

// 승인 응답 필드만 담는다. 전화번호는 구분 문자 없는 정규화 형식으로 주고받고 화면에서 포맷한다.
const mockMe = {
  loginId: 'owner01',
  email: 'owner@test.com',
  name: '김사장',
  phone: '01012345678',
  role: 'OWNER',
  status: 'ACTIVE'
}

/** 내 정보 조회 (AUTH-008) */
export async function getMe() {
  if (USE_MOCK) return { ...mockMe }
  const { data } = await http.get('/users/me')
  return data
}

/**
 * 내 정보 수정 (AUTH-008). PATCH Body 는 `phone` 만 허용한다.
 * `loginId`·`email`·`name`·`role`·`status` 를 함께 보내면 서버가 무시하지 않고
 * 400 VALIDATION_ERROR 로 거부하므로 절대 싣지 않는다.
 */
export async function updateMe({ phone }) {
  const normalized = normalizePhone(phone)
  if (USE_MOCK) return { ...mockMe, phone: normalized }
  const { data } = await http.patch('/users/me', { phone: normalized })
  return data
}

/** 비밀번호 변경 (명세 8). 현재 비밀번호 불일치 시 400 */
export async function changePassword({ currentPassword, newPassword }) {
  if (USE_MOCK) return
  await http.patch('/users/me/password', { currentPassword, newPassword })
}

/** 회원 탈퇴 (USER-004). 잔액·예치금·진행 근무 존재 시 409 */
export async function deleteMe({ password }) {
  if (USE_MOCK) return
  await http.post('/users/me/withdrawal', { password })
}

/**
 * 내 뱃지 조회 (SPEC-178-06 · API_SPEC '최신 뱃지').
 *
 * #182 로 실 Endpoint 가 살아 Mock 분기를 제거했다. 이전 Mock 은 역할과 무관하게
 * `TRUST_OWNER` 를 고정 반환해 WORKER 마이페이지에 OWNER 산정치를 그렸다 — 같은 실수가
 * 돌아오지 않도록 여기서는 서버 응답만 반환하고, 응답의 `badgeType` 이 화면 역할과
 * 맞는지는 `useTrustBadge` 가 판정한다.
 *
 * 이력이 없는 사용자도 `null` 이 아니라 `level=0`, `recentCount=0` 객체를 받는다.
 */
export async function getBadge() {
  const { data } = await http.get('/users/me/badge')
  return data
}
