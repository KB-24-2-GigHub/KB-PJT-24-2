/**
 * 서버에 아직 없는 Endpoint 기능의 단일 원천.
 *
 * 값은 이 화면이 기다리는 추적 이슈 번호다. 여기 등록된 동안 관련 화면은 제출·확인
 * 동작을 막고 '준비 중' 안내를 보여준다. 해당 이슈의 Endpoint 가 배포되면 이 객체에서
 * 항목만 지우면 화면은 다시 정상 동작한다 — 화면은 이 값의 존재 여부만 참조하므로
 * (v-if 로 안내를, :disabled 로 제출을 이 값에 직접 묶는다) 항목 삭제만으로 복구된다.
 */
export const PENDING_FEATURES = {
  WITHDRAWAL: 188 // POST /api/users/me/withdrawal — 서버 미구현
}
