---
patch_id: SPEC-282-01
status: draft
issue: 282
base_spec_version: 5.0.0
targets:
  - requirement: MVP-P0-SCENARIOS
  - requirement: INVITE-005
  - requirement: SETTLE-006
  - rest_operation: MVP-P0-OPERATIONS
  - decision: DEC-MVP-SCOPE-PRIORITY
  - decision: DEC-WORK-LONG-LIVED-LIFECYCLE
  - decision: DEC-MVP-GAP-CLASSIFICATION
  - traceability: MVP-P0-TRACEABILITY
---

# SPEC-282-01: MVP P0 제품 기준선과 추적성 릴리스

## 추가 사항

- 외부 입력 `MVP_SCOPE.md`의 37개 P0 시나리오 행을 프로젝트의 최상위 제품 목표로
  채택하고, 기존 5.0.0 계약과 충돌하는 우선순위·사용자 결과는 MVP 범위를 기준으로
  6.0.0 계약에서 재분류한다.
- 각 P0 단계에 시나리오 ID, 역할, 선행 상태, 사용자 행동, 성공 결과, 대표 실패,
  요구사항 ID, 목표 REST Operation, 인증·권한, 요청·응답·대표 4xx, 재시도 의미와
  선행·후행 단계를 연결한다.
- 생성, 초대 발급, 수락과 계약 확정은 수분 또는 수시간에 걸친 서로 다른 요청이다.
  각 요청은 짧은 Transaction을 완료하고, `DRAFT`·`PENDING` 중간 상태와 재로그인·새로고침을
  정상 흐름으로 취급하며 수락 시점에 조건 Version, 만료, 당사자와 자금을 다시 검증한다.
- 현재 구현 상태는 `dev2` 기준 코드·설정·집중 테스트와 GitHub 이슈 상태로 감사해
  `Implemented`, `Partial`, `Planned`, `Blocked`로 구분한다. 구조 변경 대상은 별도
  `Refactoring` 연결로 표시하며 목표 API 기재를 구현 완료 증거로 사용하지 않는다.
- 시급 입력에서 약정 일급을 산정하는 계약, 초대의 보건증 제출 요구 전달, 지각 차감액의
  WORKER 부분 지급과 OWNER 환불은 MVP P0 목표로 유지한다. 현재 계약·구현·기능 이슈가
  완전한 실행 규칙이나 소유자를 제공하지 않는 부분은 `Blocked` 기능 공백으로 기록하고
  리팩터링 이슈에서 임의 구현하지 않는다.
- M4~M7 기능 이슈는 현재 마일스톤과 상태를 유지한 채 P0 구현 소유자 또는 검증
  선행조건으로 연결한다. 중복 이슈를 만들거나 Refactoring 마일스톤으로 이동하지 않는다.
- P1은 P0 흐름을 대체하지 않는 보조 시연 기능, Deferred는 이번 시연·Refactoring 릴리스에서
  구현하지 않는 기능으로 명시하고 기존 기능 계약을 삭제하지 않는다.

## 완료 조건

- [ ] 37개 P0 시나리오가 요구사항·API·결정·추적 문서에서 누락 없이 교차 참조된다.
- [ ] P0 Operation마다 역할·권한, 요청, 성공 응답, 대표 오류, 재요청 의미와 흐름 순서가
      확인된다.
- [ ] 시급 산정, 보건증 요구와 지각 차감·부분 환불 공백이 P0에서 내려가지 않고 현재
      상태와 구현 소유자 부재를 정확히 드러낸다.
- [ ] 장기 Work 생명주기가 서버 Thread, 장기 Transaction, 메모리 Session 상태를 점유하지
      않는 요청 간 영속 상태 흐름으로 고정된다.
- [ ] M4~M7의 기존 기능 이슈와 현재 Open/Closed 상태가 실제 GitHub 상태에 맞게 연결된다.
- [ ] 6.0.0 버전, 릴리스 기록, 요구사항, API, 결정, 추적성과 `SPEC_LOCK.json`이 하나의
      관리자 수락 릴리스로 갱신된다.
- [ ] Patch는 내용 변경 없이 `accepted`로 전환되어 보관되고 승인 릴리스에 관련 `draft`가
      남지 않는다.
- [ ] 보호 명세 Lock, Patch 수명주기, Markdown 형식, 로컬 링크와 Git 추적 검사가 통과한다.
