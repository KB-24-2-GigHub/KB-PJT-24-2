---
patch_id: SPEC-387-01
status: draft
issue: 387
base_spec_version: 8.0.0
targets:
  - requirement: DISPUTE-001
  - requirement: DISPUTE-002
  - requirement: DISPUTE-003
  - operation: POST /api/work-cases/{workCaseId}/disputes
  - operation: GET /api/work-cases/{workCaseId}/disputes
  - decision: DEC-DISPUTE-SETTLEMENT
  - decision: DEC-OPEN-ADMIN-DISPUTE
---

# SPEC-387-01: LLM 외부 분쟁조정 DEMO

## 추가 사항

- LLM은 실제 법률 판단자가 아니라 외부 분쟁조정 시스템을 흉내 내는 DEMO Provider다.
  설정은 `DISABLED`, `FAKE`, `DEMO_LLM`으로 나누며 실제 자금 모드에서는 `DEMO_LLM`을
  활성화할 수 없다.
- OWNER와 배정 WORKER는 Work Case가 `ACCEPTED`, `READY`, `IN_PROGRESS`,
  `CHECK_OUT_MISSING`, `COMPLETED`, `NO_SHOW`일 때 분쟁을 생성·조회할 수 있다. 이미 지급·
  환불이 끝난 분쟁도 기록과 검토는 허용하지만 완료된 자금 이동은 되돌리지 않는다.
- 신고 Transaction은 분쟁을 `OPEN`으로 저장하고 정상 `SCHEDULED` 정산을 기존 `due_at`을
  보존한 `ON_HOLD`로 바꾼 뒤 끝난다. 외부 Provider 호출은 Commit 뒤에 실행하며 DB Lock이나
  자금 Transaction을 유지한 채 호출하지 않는다.
- Provider 입력은 신고 제목·경위, 근태 상태, 약정 일급과 정산 상태처럼 판단에 필요한 최소
  사실만 포함한다. 이름, 전화번호, 계좌번호, 사용자·근무·정산의 내부 ID는 포함하지 않는다.
- Provider 출력은 `decision`, `reasonCodes`, `summary`, `confidence`의 고정 JSON이다.
  `RESOLVE`는 `RESOLVED`, `REJECT`는 `REJECTED`, `NEEDS_MORE_INFO`는
  `UNDER_REVIEW`로 반영한다. LLM은 지급액·환불액을 만들거나 기존 금액을 다시 계산하지 않는다.
- `RESOLVED`와 `REJECTED`가 마지막 열린 분쟁을 닫으면 정상 정산은 기존 `due_at`의
  `SCHEDULED`로 돌아가고 NO_SHOW 환불은 다시 승인할 수 있다. `UNDER_REVIEW`는 보류를
  유지한다.
- Timeout, 5xx, 거부, 형식·값 검증 실패는 분쟁을 `UNDER_REVIEW`로 남기며 자동으로 보류를
  풀지 않는다. 늦거나 중복된 결과는 실행 상태, 현재 분쟁 상태와 입력 Snapshot Hash가 모두
  일치할 때 한 번만 반영한다.
- AI 검토 이력은 `SIMULATED_LLM` 출처, Provider·Model·Prompt Version, 입력 Hash,
  요청·응답 식별자, 결과 또는 실패 사유와 처리 시각을 보존한다. API Key와 원문 개인정보는
  저장하지 않는다.
- 분쟁 Page Item의 기존 필드는 유지하고 nullable `demoReview`를 추가한다. 검토 결과가 있으면
  `source=SIMULATED_LLM`, `decision`, `reasonCodes`, `summary`, `confidence`, `reviewedAt`을
  제공한다. 검토 전이거나 결과를 신뢰할 수 없는 실패이면 `demoReview`는 `null`이다.
- DEMO에서는 사용자 철회, 관리자 상태 변경, 강제 지급·환불 API를 제공하지 않는다.
  `CANCELED`은 이번 기능에서 새로 만드는 전이가 아니다.

## 완료 조건

- [ ] 당사자의 신고가 열린 분쟁 한 건으로 저장되고 정상 정산 또는 NO_SHOW 환불을 보류한다.
- [ ] 신고 Commit 뒤 실행된 Fake Provider 결과가 세 결정과 분쟁 상태에 결정적으로 매핑된다.
- [ ] 종료 결과만 기존 정산 흐름을 재개하고 추가 자료 요청과 모든 Provider 실패는 보류를 유지한다.
- [ ] 중복 실행, 지연 응답과 지급·환불 경합에서도 자금과 원장이 한 번만 움직인다.
- [ ] OWNER와 WORKER가 같은 상태, 요약, 사유 코드와 처리 시각을 Page API와 화면에서 확인한다.
- [ ] 화면은 모든 AI 결과를 법적 판단이 아닌 DEMO 시뮬레이션으로 표시한다.
- [ ] 실제 MySQL과 Browser DEMO에서 정상 정산·NO_SHOW·장애 시나리오를 재현한다.

## 범위 제외

- 실제 고용노동청·법원 연동과 법률 자문
- 실자금 모드의 LLM 자동 판정
- 부분 지급, 임금·세금 재계산과 손해배상
- 사용자 철회, 관리자 사건 처리, 강제 지급·환불과 알림 채널
