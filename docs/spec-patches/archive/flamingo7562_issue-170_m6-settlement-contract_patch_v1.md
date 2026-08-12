---
patch_id: SPEC-170-01
status: accepted
issue: 170
base_spec_version: 6.0.1
targets:
  - requirement: SETTLE-001
  - requirement: SETTLE-002
  - requirement: SETTLE-003
  - requirement: SETTLE-004
  - requirement: SETTLE-005
  - requirement: SETTLE-006
  - requirement: WALLET-005
  - requirement: WALLET-006
  - requirement: DISPUTE-001
  - requirement: DISPUTE-002
  - requirement: DISPUTE-003
  - operation: POST /api/work-cases/{workCaseId}/settlement/approve
  - operation: POST /api/work-cases/{workCaseId}/settlement/no-show-refund/approve
  - operation: GET /api/work-cases/{workCaseId}/disputes
  - operation: POST /api/work-cases/{workCaseId}/disputes
  - decision: DEC-SETTLEMENT-LIFECYCLE
  - decision: DEC-SETTLEMENT-IDEMPOTENCY
  - decision: DEC-SETTLEMENT-SCHEDULER
  - decision: DEC-DISPUTE-SETTLEMENT
  - decision: DEC-NO-SHOW-SETTLEMENT
  - decision: DEC-SETTLEMENT-ERROR-CATALOG
---

# SPEC-170-01: M6 정산 시간·멱등·노쇼·분쟁 계약

## 추가 사항

### 정상 정산 생명주기와 시간 경계

- 초대 수락 Aggregate는 합의 일급의 Settlement를 `WAITING`, `due_at=null`로 만듭니다.
- 정상 또는 확인된 CHECK_OUT은 Work Case를 `COMPLETED`, Settlement를 `SCHEDULED`,
  `due_at=recordedAt+24시간`으로 한 Transaction에서 바꿉니다.
- OWNER 즉시 승인은 Work Case가 이미 `COMPLETED`이고 Settlement가 `SCHEDULED`, Escrow가
  `HELD`일 때만 가능합니다. Settlement는 Work Case 상태를 만들거나 바꾸지 않습니다.
- `due_at`은 OWNER API의 만료 시각이 아니라 Scheduler가 추가로 지급 자격을 얻는 경계입니다.
  OWNER는 Scheduler가 선점하기 전까지 `due_at` 전후 모두 승인할 수 있습니다.
- Scheduler의 후보 판정은 MySQL `NOW(6)`을 사용하고 `due_at <= NOW(6)`을 포함합니다. API의
  시각은 UTC `Instant`, DB의 `DATETIME(6)`은 `Asia/Seoul` 벽시계로 변환합니다.
- OWNER와 Scheduler는 같은 Work Case·Settlement 잠금과 원자 지급 실행기를 사용합니다.
  `SCHEDULED → PROCESSING`을 먼저 조건부 전이한 한 실행만 자금을 이동하고 패자는 완료 상태를
  확인한 뒤 중복 지급하지 않습니다.
- `PROCESSING` 전이, Escrow 해제, OWNER locked 감소, WORKER available 증가, 양측
  `ESCROW_RELEASE` 원장과 `COMPLETED` 전이는 하나의 Transaction입니다. 실패하면 모두
  Rollback해 `SCHEDULED`로 남습니다.
- `CHECK_OUT_MISSING`은 결정이 추가로 승인될 때까지 `WAITING/due_at=null`이며 지급·환불하지
  않습니다.

| 현재 상태 | 허용 전이 | 의미 |
| --- | --- | --- |
| `WAITING` | `SCHEDULED`, `PROCESSING` | 정상 CHECK_OUT 예약 또는 NO_SHOW 환불 승인 대기 |
| `SCHEDULED` | `ON_HOLD`, `PROCESSING` | OWNER 또는 due Scheduler가 지급할 수 있는 정상 정산 |
| `ON_HOLD` | `SCHEDULED` | 열린 임금분쟁으로 정상 지급이 보류됨; `due_at` 보존 |
| `PROCESSING` | `COMPLETED`, `REFUNDED` | 같은 자금 Transaction 안에서만 존재하는 중간 상태 |
| `COMPLETED` | 없음 | 정상·지각 지급이 완료된 재처리 불가 상태 |
| `REFUNDED` | 없음 | NO_SHOW 전액 환불이 완료된 재처리 불가 상태 |
| `FAILED` | 관리자 승인 복구만 | 자동 지급이 제한 재시도를 소진한 상태; 자동 재처리 금지 |

### 열린 임금분쟁의 보류와 재개

- `OPEN`, `UNDER_REVIEW` 분쟁은 정상 지급과 NO_SHOW 환불을 모두 막습니다.
- 정상 `SCHEDULED` 정산에 분쟁을 등록하면 같은 Transaction에서 `ON_HOLD`로 전이하되
  `due_at`, Escrow와 Wallet·원장을 바꾸지 않습니다.
- `RESOLVED`, `REJECTED`, `CANCELED`은 닫힌 분쟁입니다. 마지막 열린 분쟁을 닫는 Transaction은
  정상 정산을 `ON_HOLD → SCHEDULED`로 복구하고 기존 `due_at`을 보존합니다. 이미
  `due_at`이 지났으면 다음 Scheduler 주기에 즉시 후보가 됩니다.
- `NO_SHOW`의 `WAITING` 정산은 별도 상태로 바꾸지 않고 열린 분쟁이 존재하는 동안 환불
  승인을 `409 SETTLEMENT_ON_HOLD`로 거부합니다. 분쟁이 닫히면 다시 승인할 수 있습니다.
- 분쟁 등록과 지급은 `work_cases → settlements → disputes` 순서로 직렬화합니다. 분쟁이 먼저
  Commit되면 지급을 막고, 지급이 먼저 Commit되면 후속 신고가 이미 완료된 자금 이동을
  되돌리지 않습니다.
- 분쟁 등록 Body는 `{title, content}`입니다. `title`은 trim 후 1~100자, `content`는 trim 후
  1~2000자이며 서버가 `dispute_type=WAGE`로 기록합니다. 근무 OWNER와 배정 WORKER만 생성·
  조회하고 Work Case당 열린 분쟁은 하나만 허용합니다.
- 생성은 `201 {data:{reportId}}`, 조회는 `reportId`, `title`, `content`, `status`,
  `resolution`, `requesterRole`, `createdAt`, nullable `resolvedAt`의 Page를 반환합니다. 내부
  사용자 ID와 처리자 ID는 반환하지 않습니다.
- 관리자 역할과 상태 변경 Endpoint는 `DEC-OPEN-ADMIN-DISPUTE`가 닫힐 때까지 구현하지
  않습니다. 이 Patch는 닫힌 상태가 정산을 재개하는 자금 계약만 고정합니다.

### 외부 승인 멱등성과 원장 Key

- OWNER 정상 지급의 Operation은 `SETTLEMENT_APPROVE`, Fingerprint는
  `SHA-256(UTF-8("SETTLEMENT_APPROVE\n" + decimalWorkCaseId))`입니다.
- OWNER NO_SHOW 환불의 Operation은 `SETTLEMENT_NO_SHOW_REFUND_APPROVE`, Fingerprint는
  `SHA-256(UTF-8("SETTLEMENT_NO_SHOW_REFUND_APPROVE\n" + decimalWorkCaseId))`입니다.
- 두 Operation은 `(인증 사용자, Operation, Idempotency-Key)`의 기존
  `idempotency_requests` Claim을 사용합니다. 성공 Body를 24시간 보존하고 같은 Key·Fingerprint는
  현재 상태와 무관하게 저장한 200을 `Idempotency-Replayed: true`와 함께 반환합니다.
- 같은 Key가 처리 중이면 `409 CONFLICT`, 다른 Fingerprint에 재사용되면
  `409 IDEMPOTENCY_KEY_REUSED`입니다. 본 처리 실패 Claim은 제거합니다.
- 외부 Key 원문을 금융 원장 Key로 사용하지 않습니다. 정상 지급의 OWNER·WORKER 원장 Key는
  Settlement ID와 각각 `SETTLEMENT_RELEASE_OWNER`, `SETTLEMENT_RELEASE_WORKER` Namespace의
  SHA-256으로 결정합니다. 수동 승인과 Scheduler가 같은 두 Key를 사용합니다.
- NO_SHOW 환불 원장 Key는 Settlement ID와 `SETTLEMENT_REFUND_OWNER` Namespace의 SHA-256으로
  결정합니다. `wallet_transactions.idempotency_key` Unique가 상태 전이와 함께 중복 자금
  이동을 최종 방어합니다.
- 같은 Key Replay만 200입니다. 다른 Key나 Scheduler가 이미 완료한 정산을 승인하면
  `409 SETTLEMENT_ALREADY_PROCESSED`이며 현재 완료 결과를 새 성공으로 만들지 않습니다.

### Scheduler 선점·재시도·재시작

- Scheduler는 외부 HTTP Endpoint와 임의 OWNER 승인자를 만들지 않습니다. 자동 지급의
  `approved_by_user_id`는 `null`이고 내부 Operation 식별자는 Settlement ID 기반
  `SETTLEMENT_SCHEDULED_PAYOUT`입니다.
- 한 실행은 `SCHEDULED`, `due_at <= NOW(6)`, 열린 분쟁 없음,
  `next_retry_at IS NULL OR next_retry_at <= NOW(6)` 후보를 `due_at ASC, id ASC`로 최대 100건
  처리합니다. 각 후보는 짧은 독립 Transaction에서 `FOR UPDATE SKIP LOCKED`와 조건부 전이로
  선점합니다.
- Deadlock, Lock Timeout과 일시 Adapter 실패는 자금 Transaction 전체를 Rollback한 뒤 별도
  짧은 감사 Transaction에서 `retry_count`, `failure_code`, `last_failure_at`, `next_retry_at`을
  갱신합니다. 재시도 간격은 1분, 5분, 15분, 60분이며 이후 60분을 유지합니다.
- 총 다섯 번 실패하면 돈·원장이 움직이지 않았음을 재검증한 뒤 `FAILED`로 전이하고 자동
  재처리를 멈춥니다. 상태·금액 무결성 실패도 즉시 `FAILED`와 닫힌 `failure_code`를 남기며
  운영자가 원인을 확인하기 전 임의 정상화하지 않습니다.
- `PROCESSING`은 자금 Transaction과 따로 Commit하지 않으므로 프로세스 중단은
  `SCHEDULED`로 Rollback됩니다. 재시작한 인스턴스는 `next_retry_at`이 지난 후보를 다시
  선점합니다. 과거에 남은 `PROCESSING` 행은 자동 지급하지 않고 수동 대사 대상으로
  격리합니다.

### NO_SHOW 환불 종료와 Schema 경계

- 성공 CHECK_IN이 없는 `NO_SHOW`, `WAITING/due_at=null`, `HELD` Escrow만 해당 OWNER가 별도
  멱등 Operation으로 환불 승인할 수 있습니다. 자동 NO_SHOW 환불은 제공하지 않습니다.
- 승인 Transaction은 Settlement `PROCESSING → REFUNDED`, Escrow `HELD → REFUNDED`, OWNER
  locked 감소·available 증가와 OWNER `ESCROW_REFUND` 한 건을 함께 Commit합니다. WORKER
  Wallet과 원장은 바꾸지 않습니다.
- 성공 Body의 `status`는 `REFUNDED`이고 `originalEscrowAmount`, `workerPaidAmount=0`,
  `ownerRefundAmount=originalEscrowAmount`, `completedAt`을 반환합니다.
- 현재 Flyway `202608061428`의 범용 Claim과 `(status,due_at)` Index는 재사용합니다. 후속 #171은
  기존 Migration을 수정하지 않고 `REFUNDED`, Scheduler 재시도 필드, `disputes.title`과 안전한
  상태·시각 결합 제약만 신규 immutable Migration으로 추가합니다.
- 기존 행은 민감정보 없는 상태별 count로 먼저 감사합니다. 의미를 단일하게 복원할 수 없는
  `COMPLETED + due_at IS NULL`, 고착 `PROCESSING` 또는 금액·원장 모순을 추정 Backfill하지 않고
  격리·수동 조치합니다.

### 오류 계약

| 상황 | HTTP | Code |
| --- | ---: | --- |
| 인증 없음 | 401 | `AUTH_REQUIRED` |
| OWNER 역할 불일치 | 403 | `ROLE_MISMATCH` |
| 다른 OWNER의 Work Case 또는 존재하지 않는 Work Case | 404 | `RESOURCE_NOT_FOUND` |
| 열린 분쟁으로 지급·환불 보류 | 409 | `SETTLEMENT_ON_HOLD` |
| Work·Settlement·Escrow가 승인 가능한 상태가 아님 | 409 | `SETTLEMENT_NOT_READY` |
| 다른 Key 또는 Scheduler가 이미 처리함 | 409 | `SETTLEMENT_ALREADY_PROCESSED` |
| 같은 Key가 같은 Fingerprint를 처리 중 | 409 | `CONFLICT` |
| 같은 Key를 다른 Fingerprint에 재사용 | 409 | `IDEMPOTENCY_KEY_REUSED` |
| 열린 분쟁 중복 등록 | 409 | `DISPUTE_ALREADY_OPEN` |
| 제한 재시도 뒤 일시 장애 지속 | 503 | `SETTLEMENT_TEMPORARILY_UNAVAILABLE` |
| 상태·금액·원장 무결성 모순 | 500 | `INTERNAL_ERROR` |

## 완료 조건

- [ ] 정상 CHECK_OUT의 `COMPLETED + SCHEDULED/due_at`과 OWNER·Scheduler 자격 경계가 DB 시각
      기준으로 고정된다.
- [ ] OWNER와 Scheduler가 같은 원자 지급 실행기·결정적 원장 Key를 사용하고 어떤 경합에서도
      자금과 양측 원장이 한 번만 이동한다.
- [ ] 열린 분쟁은 지급·NO_SHOW 환불을 막고 정상 Settlement를 `ON_HOLD`로 표시하며, 닫힌
      분쟁은 기존 `due_at`을 보존한 채 지급을 재개한다.
- [ ] 같은 Key는 저장된 200을 정확히 Replay하고 다른 Key·자동 실행 패자는 승인 오류로
      끝나며 새 자금 이동을 만들지 않는다.
- [ ] 자동 지급의 선점·Batch·Backoff·최종 실패·재시작 복구와 감사 필드 의미가 확정된다.
- [ ] NO_SHOW 환불이 `REFUNDED`, Escrow `REFUNDED`, OWNER 전액 반환과 WORKER 무변경으로
      재처리 불가 종료된다.
- [ ] `CHECK_OUT_MISSING`은 추가 결정 전 `WAITING/due_at=null`과 HELD 자금을 유지한다.
- [ ] #171의 최소 신규 Migration 범위와 추정 Backfill 금지 원칙이 확정된다.
- [ ] REQUIREMENTS, API_SPEC, DECISIONS, SPEC_TRACEABILITY, 릴리스 버전과 `SPEC_LOCK.json`이
      같은 계약으로 정렬된다.
