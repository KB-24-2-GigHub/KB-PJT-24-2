---
patch_id: SPEC-328-01
status: accepted
issue: 328
base_spec_version: 7.0.1
targets:
  - requirement: MVP-P0-SCENARIOS
  - requirement: DASH-001
  - requirement: DASH-002
  - requirement: WORK-003
  - requirement: WORK-008
  - requirement: INVITE-003
  - requirement: SETTLE-002
  - requirement: SETTLE-003
  - requirement: SETTLE-004
  - requirement: SETTLE-006
  - operation: POST /api/workplaces/{workplaceId}/work-cases
  - operation: PATCH /api/work-cases/{workCaseId}
  - operation: GET /api/invitations/{token}
  - operation: GET /api/worker/home
  - operation: GET /api/worker/work-cases
  - operation: POST /api/work-cases/{workCaseId}/settlement/approve
  - decision: DEC-MVP-SCOPE-PRIORITY
  - decision: DEC-MVP-SETTLEMENT-CONSERVATION
  - decision: DEC-WAGE-CALCULATION
  - decision: DEC-LATE-DEDUCTION
  - decision: DEC-ATTENDANCE-RESPONSE-SHAPES
  - traceability: MVP-P0-TRACEABILITY
---

# SPEC-328-01: 시급 계약을 제거하고 약정 일급을 금액 원본으로 사용한다

## 추가 사항

MVP Work Case의 금액 원본은 OWNER가 직접 입력한 `dailyWage`이며, 서버는 이를
`work_cases.agreed_wage`에 약정 일급으로 저장한다. `hourlyWage` 또는 `hourly_wage`를 새로
저장하거나 일급에서 시급을 역산하지 않고, 시작·종료·휴게시간으로 일급을 다시 계산하지
않는다. 등록·수정·초대·계약·예치·조회·정산은 같은 약정 일급 Snapshot을 사용한다.

Work Case 등록·수정 요청은 기존 금액 필드 `hourlyWage` 대신 양의 원 단위 정수
`dailyWage`를 필수로 사용한다. 초대, Work Case와 WORKER 홈·이력 응답은 `dailyWage`만
금액 조건으로 반환하고 `hourlyWage`, `expectedDeductionAmount`,
`expectedPaymentAmount`를 반환하지 않는다.

`lateMinutes`는 성공 CHECK_IN으로부터 파생하는 근태 정보로 유지하지만 자동 금액 조정의
입력으로 사용하지 않는다. 시급 기반 예상 공제·실시간 확보 금액·지각 분할 정산은 현재 MVP
계약에서 제외하며 다른 공제식을 추정하지 않는다. 정상 또는 지각 상태에서 완료된 Work Case의
현재 정산은 원 예치액인 약정 일급 전액을 WORKER에게 지급하고 OWNER 환불은 0원이다. NO_SHOW
전액 환불 계약은 변경하지 않는다. 향후 지각 공제나 시간 경과 확보 금액을 도입하려면 별도
제품 결정과 새 명세 Patch가 필요하다.

최상위 시연 시나리오는 근무 등록·초대 확인에서 시급 대신 약정 일급을 표시하도록 정정한다.
지각 시연은 지각 분수를 화면에서 확인하는 데까지를 P0로 유지하고, 시급 기반 차감 예정액과
부분 지급·부분 환불을 P0 성공 조건에서 제거한다. 일급의 전액 지급 또는 NO_SHOW 전액 환불이
각각 원 예치액을 한 번만 소진한다는 보존식은 유지한다.

이 정정은 코드, Migration, DDL 또는 Backfill을 요구하지 않는다. 수락된 `SPEC-282-01`과
`SPEC-165-01`은 당시 계약의 감사 기록으로 수정하지 않는다.

## 완료 조건

- [ ] 활성 Work Case 등록·수정 요청이 `dailyWage`를 필수로 사용하고 `hourlyWage`를 요구하지
      않는다.
- [ ] 초대·Work Case·WORKER 홈·이력 응답이 약정 일급 `dailyWage`를 사용하고 시급 및 시급
      기반 예상 공제·지급 필드를 반환하지 않는다.
- [ ] `lateMinutes`는 근태 정보로 남지만 현재 MVP의 자동 공제나 분할 정산 입력으로 사용되지
      않는다.
- [ ] 정상·지각 완료 정산은 약정 일급 전액을 WORKER에게 지급하고 OWNER 환불은 0원이며,
      NO_SHOW 전액 환불 계약은 그대로다.
- [ ] 시간 경과 확보 금액과 지각 분할 정산은 P0 완료 조건이나 미구현 Blocker로 남지 않고,
      향후 별도 결정이 필요한 범위로 분류된다.
- [ ] `MVP_SCOPE.md`, 요구사항, API, 결정과 추적 문서가 같은 일급 계약을 가리킨다.
- [ ] 새 시급 Column, Migration, DDL, Backfill과 일급으로부터의 시급 역산이 추가되지 않는다.
