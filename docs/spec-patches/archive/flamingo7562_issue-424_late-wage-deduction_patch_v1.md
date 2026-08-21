---
patch_id: SPEC-424-01
status: accepted
issue: 424
base_spec_version: 8.1.0
targets:
  - requirement: DASH-001
  - requirement: DASH-003
  - requirement: ATT-004
  - requirement: ATT-006
  - requirement: SETTLE-001
  - requirement: SETTLE-002
  - requirement: SETTLE-003
  - requirement: SETTLE-004
  - requirement: SETTLE-005
  - requirement: SETTLE-006
  - requirement: WALLET-004
  - requirement: WALLET-005
  - requirement: WALLET-006
  - decision: DEC-PROPORTIONAL-ATTENDANCE-DEDUCTION
  - decision: DEC-MVP-SETTLEMENT-CONSERVATION
  - decision: DEC-NO-AUTOMATIC-LATE-DEDUCTION
  - decision: DEC-DAILY-WORKER-TAX
  - decision: DEC-CHECK-OUT-MISSING
  - decision: DEC-DISPUTE-SETTLEMENT
  - decision: DEC-SETTLEMENT-LIFECYCLE
  - decision: DEC-SETTLEMENT-IDEMPOTENCY
  - operation: GET /api/worker/home
  - operation: GET /api/work-cases/{workCaseId}
  - operation: POST /api/work-cases/{workCaseId}/settlement/approve
  - operation: POST /api/work-cases/{workCaseId}/settlement/no-show-refund/approve
  - operation: POST /api/work-cases/{workCaseId}/settlement/check-out-missing-refund/approve
  - operation: GET /api/wallet/transactions
---

# SPEC-424-01: 지각·조퇴 비례 차감과 예치금 분할 정산

## 추가 사항

### 하나의 근태 비례 공식으로 지급액과 환불액을 확정한다

약정 일급 `A`는 OWNER가 입력해 `work_cases.agreed_wage`에 저장된 값이다. 전체 예정 분을
`S`, 무급 휴게 분을 `B`라 하고 유급 휴게이면 `B=0`으로 둔다. 차감 분모는 `D=S-B`이며
항상 양수여야 한다. 휴게 배치 시각은 저장하지 않으므로 지각·조퇴 구간과 휴게의 실제 중첩은
판별하지 않고, 무급 휴게 총분만 분모에서 제외한다.

성공 CHECK_IN의 서버 `attemptedAt`이 예정 시작을 초과한 양의 시간을 분 단위로 올림한 값을
지각 분 `L`로 사용한다. 성공 CHECK_OUT이 예정 종료보다 이른 양의 부족 시간을 같은 방식으로
올림한 값을 조퇴 분 `E`로 사용한다. 조기 출근과 예정 종료 뒤 퇴근은 추가 임금을 만들지
않는다. `M=min(D,L+E)`로 합산해 지각과 조퇴를 두 번 차감하지 않는다.

- `M=0`: WORKER 지급액 `P=A`
- `M>0`: `P=floor10(A*(D-M)/D)`로 최종 지급액의 10원 미만을 버린다.
- OWNER 환불액 `R=A-P`

최대 차감액은 `A`이고 0원 지급을 허용한다. 모든 결과는 `A=P+R`, `P>=0`, `R>=0`을
만족한다. 성공 CHECK_OUT 결과는 `P=0`이어도 근무 수행 공식에 따른 완결이므로 Settlement
`COMPLETED`, Escrow `RELEASED`로 종료한다.

### 최초 계산 Snapshot만 자금 실행에 사용한다

Settlement는 약정액과 별도로 WORKER 지급액, OWNER 환불액, 분모, 지각 분, 조퇴 분,
계산 사유·Version·시각을 보존한다. 권위 있는 Snapshot은 성공 CHECK_OUT Transaction,
NO_SHOW 전이 Transaction, CHECK_OUT_MISSING 전이 Transaction에서 각각 한 번만 기록한다.
근무 중 표시값은 자금 실행 근거가 아닌 참고용 값이다.

NO_SHOW와 CHECK_OUT_MISSING Snapshot은 `P=0`, `R=A`다. 판정 시점에는 Wallet·Escrow·원장을
움직이지 않는다. OWNER 승인, 자동 지급, 분쟁 보류·재개, 실패 재시도와 응답 유실 Replay는
저장된 Snapshot만 사용하며 계산하지 않는다. Scheduler 실행 주기와 실제 Poll 시각도 계산
입력으로 사용하지 않는다.

성공 CHECK_OUT은 기존처럼 Settlement를 `SCHEDULED`, `dueAt=attemptedAt+24시간`으로 만든다.
OWNER 승인과 Scheduler는 같은 원자 실행기와 결정적 원장 Key로 경합한다. `P>0`이면 OWNER
잠금 감소와 WORKER 가용 증가를 나타내는 양측 `ESCROW_RELEASE`를, `R>0`이면 OWNER 잠금
감소·가용 증가를 나타내는 `ESCROW_REFUND`를 같은 Transaction에서 기록한다. 금액이 0인
Wallet Transaction은 만들지 않으며 원 예치액은 잔액 없이 소진한다.

### CHECK_OUT_MISSING 환불은 별도 OWNER Operation으로 승인한다

`POST /api/work-cases/{workCaseId}/settlement/check-out-missing-refund/approve`는 OWNER 전용,
0byte Body, CSRF와 `Idempotency-Key`가 필요한
`SETTLEMENT_CHECK_OUT_MISSING_REFUND_APPROVE` Operation이다.

대상은 성공 CHECK_IN이 있고 성공 CHECK_OUT이 없는 `CHECK_OUT_MISSING`, Settlement
`WAITING/dueAt=null`, Escrow `HELD`, 저장 Snapshot `P=0/R=A`인 Work Case다. 열린 분쟁은
`409 SETTLEMENT_ON_HOLD`로 환불을 막는다. 성공하면 Settlement와 Escrow를 `REFUNDED`로
종료하고 OWNER 전액 환불 원장만 남긴다. WORKER Wallet과 원장은 바꾸지 않는다. 외부
Operation과 멱등 Claim은 NO_SHOW 승인과 분리하고, 내부 환불 실행기와 Settlement별 결정적
OWNER 환불 원장 Key는 공유한다.

### 조회와 화면은 동일한 Snapshot을 표시한다

정산 조회와 승인 응답은 `originalEscrowAmount`, `workerPaidAmount`, `ownerRefundAmount`,
`deductionAmount`, `deductionBaseMinutes`, `lateMinutes`, `earlyLeaveMinutes`,
`calculationReason`, `calculationVersion`, `calculatedAt`을 같은 Snapshot에서 반환한다.
Snapshot 전 nullable 금액을 클라이언트가 약정액과 시간으로 권위 있게 재계산하지 않는다.

WORKER와 OWNER는 승인 전에 약정액·지각·조퇴·차감·지급·환불 근거를 확인하고 완료 뒤 같은
값을 본다. 거래내역은 WORKER 지급 `ESCROW_RELEASE`와 OWNER 차액 환불 `ESCROW_REFUND`를
구분한다.

GigHub는 세금을 원천징수하거나 납부하지 않으며 실제 WORKER Wallet에는 `P` 전액을 입금한다.
최종 Snapshot 뒤 `GET /api/worker/home`은 `P`를 기준으로 기존 DASH-003 공식을 적용한
`todayWorkCase.taxReference.basisAmount`, `estimatedTaxAmount`, `estimatedAfterTaxAmount`를
참고값으로 제공한다. Snapshot 전에는 `todayWorkCase.taxReference=null`이다. 이 필드는 기존
`todayWorkCase.expectedNetAmount`를 대체한다. 화면은 `예상 세액(참고)`와 GigHub가 실제
지급액에서 세금을 차감하지 않는다는 안내를 사용하고, 기존 `expectedNetAmount`와 `세금 공제`
표현을 실제 지급액처럼 사용하지 않는다.

### 관련 draft 계약을 이 범위에서 정렬한다

- `SPEC-168-01`의 근무 경과 참고값은 비금융 표시로 유지하되, 실제 정산 Snapshot과 세금
  참고값을 변경하거나 대신하지 않는다. 고정 `expectedNetAmount` 표시는 이 Patch가 대체한다.
- `SPEC-413-01`의 야간 근무 해석과 16시간 상한은 유지하되, 근무 길이가 정산 금액에 관여하지
  않는다는 문장은 이 Patch의 `D` 계산 계약으로 대체한다.
- `SPEC-387-01`의 LLM이 금액을 만들거나 다시 계산하지 않는 원칙은 유지한다.
  `REFUND_TO_OWNER`가 CHECK_OUT_MISSING 환불 승인 재개도 허용하도록 넓히고 부분 지급 제외
  문구는 이 Patch가 대체한다.
- `SPEC-375-01`의 NO_SHOW 판정 시 Wallet 자금을 움직이지 않는 계약은 유지한다. 판정
  Transaction에서 환불 승인용 Snapshot을 최초 기록하는 변경만 이 Patch가 추가한다.

## Migration scope

- 새 immutable Flyway는
  `V202608201125__add_settlement_calculation_snapshot.sql`이며 대상 Table은 `settlements`와
  `work_cases`다.
- `settlements.amount`는 약정액·최초 예치액으로 유지한다. `worker_paid_amount`,
  `owner_refund_amount`, `deduction_base_minutes`, `late_minutes`, `early_leave_minutes`,
  `calculation_reason`, `calculation_version`, `calculated_at`을 추가하고 Snapshot Shape,
  `A=P+R`, 비음수, 양수 분모와 생명주기 정합성을 CHECK로 제한한다.
- 신규 공식 Snapshot은 모든 계산 필드가 함께 저장되고, `LEGACY` Snapshot은 실제 과거 금액과
  계산 사유·Version·시각만 보존할 수 있다. 애플리케이션은 `calculated_at IS NULL`인 행만
  최초 산정하고 영향 행 수를 검증한다.
- 무급 휴게가 전체 예정 시간 이상인 신규·수정 Work Case는 애플리케이션과 DB에서 거절한다.
  기존 위반 행은 자동 보정하지 않고 Migration을 실패시킨다.
- 자금이 이동하지 않은 `WAITING`, `SCHEDULED`, `ON_HOLD`, `FAILED`는 저장된 종료 상태와
  근태를 사용해 새 공식을 적용한다. 아직 종료되지 않은 `WAITING`은 Snapshot을 비워 두고
  종료 전이에서 계산한다.
- 기존 `COMPLETED`와 `REFUNDED`의 금액·Wallet 원장은 바꾸거나 소급 회수하지 않고 실제
  결과를 `LEGACY` Snapshot으로 Backfill한다. `PROCESSING` 또는 원장·금액 모순이 있으면
  Migration을 실패시킨다.
- 운영 전환은 API·Attendance Scheduler·Settlement Scheduler를 정지하고 `PROCESSING=0`과
  원장 대사를 확인한 Maintenance 구간에서 Migration과 새 애플리케이션을 함께 배포한 뒤
  재개한다. 구 애플리케이션이 Snapshot 없이 새 Lifecycle 상태를 쓰는 혼합 Version 운영은
  허용하지 않는다.
- 같은 변경에서 새 빈 DB용 파생 Schema와 설명을 갱신한다. Disposable MySQL 8.4에서 빈 DB
  전체 Migration, 상태별 Backfill, CHECK·보존식, 최초 산정 경합, 수동 승인·Scheduler 경합과
  Replay를 검증하며 공유·Staging·Production DB에는 적용하지 않는다.

## 완료 조건

- [ ] 지각과 조퇴의 분 단위 올림, 무급·유급 휴게 분모, 10원 절삭과 상한을 포함한 공식이
      정상·지각·조퇴·동시 발생·0원 경계에서 결정적으로 같은 결과를 만든다.
- [ ] 정상 근무는 약정 일급 전액, 차감 근무는 계산된 `P`와 `R`, NO_SHOW와
      CHECK_OUT_MISSING은 승인 뒤 0원·전액 환불로 예치금을 남김없이 소진한다.
- [ ] Settlement Snapshot은 최초 한 번만 저장되고 수동 승인, Scheduler, 분쟁, 재시도와
      Replay가 저장값을 그대로 소비한다.
- [ ] 부분 정산의 모든 양수 Wallet Leg와 Before/After Snapshot이 한 Transaction에서 맞고,
      0원 Leg와 중복 지급·환불 원장이 생기지 않는다.
- [ ] CHECK_OUT_MISSING 환불은 별도 OWNER Operation과 Claim으로만 실행되고 열린 분쟁 중에는
      자금이 움직이지 않는다.
- [ ] WORKER·OWNER 조회, 승인 응답과 거래내역이 같은 약정액·지각·조퇴·차감·지급·환불 값을
      표시한다.
- [ ] 실제 Wallet 지급액에서 세금을 빼지 않고, 최종 지급액 기준 세금 참고값과 비원천징수
      안내만 표시한다.
- [ ] 기존 완료 원장 보존, 미실행 정산 전환, 분모 위반·PROCESSING 사전 실패가 Disposable
      MySQL Migration 검증을 통과한다.
- [ ] Backend 집중·DB 통합·Frontend 계약 테스트와 정상·30분 지각·조퇴·NO_SHOW·
      CHECK_OUT_MISSING 시연 경로가 통과한다.
