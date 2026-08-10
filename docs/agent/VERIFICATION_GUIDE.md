# 위험 기반 검증 가이드

이 문서는 리팩터링 전후에 같은 제품 위험을 같은 방식으로 검증하기 위한 실행 계약이다.
현재 관찰되는 동작을 고정하는 Characterization과 보호 명세의 목표 계약을 구분하며, 테스트가
있다는 사실만으로 미구현 P0를 완료로 판정하지 않는다.

진행률과 기능 구현 상태의 원본은 GitHub Issue·Milestone과
[`../specs/SPEC_TRACEABILITY.md`](../specs/SPEC_TRACEABILITY.md)다. 이 문서는 중앙 기능
인벤토리나 완료 현황판을 대체하지 않는다.

## 적용 원칙

1. 가장 좁은 테스트로 작업 중 피드백을 받고, 이슈 완료 경계에서 루트 검증을 한 번 실행한다.
2. 실제 Transaction·잠금·UNIQUE·FK·원장 보존은 Mock 단위 테스트가 아니라 `database` Tag로
   확인한다.
3. Controller·DTO 테스트는 Status, Envelope, Header와 허용·금지 JSON 필드를 고정한다.
4. Direct SQL은 사용자·사업장·계좌 같은 선행 Fixture나 Read Model·Schema 손상 검증에만
   쓴다. 미구현 사용자 동작을 SQL로 만들어 성공 E2E처럼 기록하지 않는다.
5. 장시간 Work 흐름은 한 Test Transaction으로 감싸지 않고 단계별 Service Transaction을
   commit한 뒤 ApplicationContext와 인증 Principal을 재구성한다.
6. 공유·Staging·Production DB에서는 DB 테스트, Seed, Flyway 검증을 실행하지 않는다.

## 위험 등급

| 등급 | 반드시 잡아야 하는 위험                                                      | 대표 증거                                           |
| ---- | ---------------------------------------------------------------------------- | --------------------------------------------------- |
| R0   | 순수 값, JSON 직렬화, 입력 경계, 빈 결과                                     | DTO·Validator·Domain value 단위 테스트              |
| R1   | 정상 요청, 인증·역할·소유권, 4xx, Status·Envelope·Header                     | Controller/MockMvc, Frontend service·view 테스트    |
| R2   | 상태 전이, 같은 Key Replay, expected-state, 전체 Rollback, 요청 간 상태 복원 | Service Proxy 통합, Idempotency, 장기 Work 생명주기 |
| R3   | 동시 자금, 잠금 순서, 원장 보존, 교차 사용자 격리, DB 제약, 희귀 손상        | 실제 MySQL Transaction·동시성·Schema 테스트         |

R0/R1이 통과해도 R2/R3을 대신하지 않는다. 반대로 R3용 SQL Fixture는 해당 HTTP API나 화면이
완성됐다는 증거가 아니다.

## 실행 계층

| 경계                | 명령                                     | 포함                                                          | 포함하지 않음        |
| ------------------- | ---------------------------------------- | ------------------------------------------------------------- | -------------------- |
| Guardrail 집중      | `npm.cmd run test:harness`               | Architecture·Spec·Migration·운영 Template Fixture             | 애플리케이션 테스트  |
| Backend 기본        | `./backend/gradlew.bat -p backend test`  | `database` Tag를 제외한 JUnit                                 | 실제 MySQL 불변식    |
| Frontend 기본       | `npm.cmd --prefix frontend run test:run` | Vitest 전체                                                   | Production 번들 생성 |
| Frontend Production | `npm.cmd --prefix frontend run build`    | Vite Production build                                         | 실제 API 활성 여부   |
| 이슈 완료           | `npm.cmd run check`                      | Guardrail, Harness, FE Test·Build, FE lint, BE Gradle `check` | Opt-in DB Test       |
| DB 집중             | 아래 `databaseTest --tests`              | 지정한 실제 MySQL Test                                        | 다른 DB Test         |
| DB 전체             | 아래 `databaseTest`                      | 모든 `database` Tag                                           | Browser E2E          |

`npm.cmd run check`의 Production build 성공은 hardcoded Mock 기능이 구현됐다는 뜻이 아니다.
Production Mock 신규 유입은 Guardrail이 막고, 기존 Mock과 미구현 경로는 담당 기능 이슈에
남긴다.

## Characterization 포트폴리오

한 행의 Test selector는 같은 위험을 대표하는 안정적인 진입점이다. 세부 메서드는 구조 변경
시 이름이 바뀔 수 있지만 요구사항과 불변식은 유지한다.

| Selector                                                                                                       | 위험  | Scenario·요구사항                                 | 현재 보장                                                                                        | Target와의 관계                                             | 판단 |
| -------------------------------------------------------------------------------------------------------------- | ----- | ------------------------------------------------- | ------------------------------------------------------------------------------------------------ | ----------------------------------------------------------- | ---- |
| `AuthControllerTest`, `AuthFlowSecurityIntegrationTest`, `AuthFlowDatabaseIntegrationTest`                     | R0~R3 | 1-1~1-2, AUTH-001·002·004~007                     | 가입 원자성, Session 복원, 역할·CSRF·리소스 거부                                                 | 현재 구현된 범위의 기준선                                   | 유지 |
| `WorkCaseControllerTest`, `WorkCaseServiceImplTest`, `WorkCaseServiceDatabaseIntegrationTest`                  | R0~R3 | 2-1~2-3·2-6, WORK-001~006                         | DRAFT CRUD, Version 증가, PENDING 철회, 소유권, 동시 수정                                        | `dailyWage` 직접 입력은 WORK-008 목표가 아닌 Partial legacy | 유지 |
| `InvitationIssue*`, `InvitationLifecycleDatabaseIntegrationTest`                                               | R1~R3 | 2-5·3-2, INVITE-001~004                           | 발급/기존 Link/재발급, Token 상태·만료·Version                                                   | 목표 필드 공백은 완료로 보지 않음                           | 유지 |
| `InvitationAccept*`, `IdempotencyClaim*`, `AcceptAggregateRowsDatabaseIntegrationTest`                         | R1~R3 | 3-3, CONTRACT-001~003, WALLET-005·006, SETTLE-001 | Claim, 원자 수락, Replay, 동시 1승, 계약·문서·예치·정산 제약                                     | 수락 Aggregate의 현재 구현 증거                             | 유지 |
| `LongLivedWorkLifecycleDatabaseIntegrationTest`                                                                | R2~R3 | 2-2→2-5→3-2→3-3                                   | 실제 DRAFT commit, Clock 전진, 초대 commit, Context·Principal 재구성, 수락 commit, 재조회·Replay | Thread·장기 Transaction 없이 DB 상태로 재개                 | 유지 |
| `FundingServiceImplTest`, `FundingIntegrityDatabaseIntegrationTest`                                            | R1~R3 | 1-5, WALLET-002·005·006                           | 정상/동시/Replay/Rollback/PIN 실패/원장                                                          | 현재 충전 계약과 정합                                       | 유지 |
| `WithdrawalServiceImplTest`, `WithdrawalIntegrityDatabaseIntegrationTest`                                      | R1~R3 | 5A-8, WALLET-003·005·006                          | 잔액·계좌 상태, Replay, 동시성, Rollback, 원장                                                   | 실제 DB 잔액 부족 무변경은 보강 후보                        | 유지 |
| `SettlementServiceTest`, `SettlementIntegrityDatabaseIntegrationTest`                                          | R1~R3 | 5A-6~8, SETTLE-002·004, WALLET-005·006            | 정상 전액 지급, 양측 원장, 보존식, Replay, Rollback, 동시성                                      | 지각 분할·노쇼 환불 구현 증거가 아님                        | 유지 |
| `AttendanceLifecycleDatabaseIntegrationTest`                                                                   | R2~R3 | 5C-1~2, ATT-005·006                               | Terminal 전이 상호배타·멱등, 자동 자금 이동 없음                                                 | Scan UI/API·환불 구현 증거가 아님                           | 유지 |
| `Document*ControllerTest`, `DocumentFileAccessServiceTest`, `DocumentAccessAuditSchemaDatabaseIntegrationTest` | R1~R3 | 3-6, DOC-002·004·009                              | 접근자 격리, 파일 Header, Checksum, 비공개 Key, 감사 행                                          | 목록 Item 전체 필드 고정은 보강 후보                        | 유지 |
| Frontend `*.spec.js`와 `*.test.js` 전체                                                                        | R0~R2 | P0 화면·Route·Service 현재 동작                   | 요청 Shape, Redirect, 중복 클릭, 일부 Mock 경계                                                  | Production build와 실제 API 활성은 별도 판정                | 유지 |

### Direct SQL 증거의 한계

- `WorkCaseServiceDatabaseIntegrationTest`의 `ACCEPTED`·`COMPLETED`·근태 행은 상세 Read
  Model을 검증하는 Fixture다. 실제 출퇴근 Scan 구현 증거가 아니다.
- `WorkCaseMapperDatabaseIntegrationTest`의 `READY` 행은 Filter·정렬·expected-state SQL
  증거다. READY 제품 흐름의 완료 증거가 아니다.
- `AcceptAggregateRowsDatabaseIntegrationTest`의 직접 생성 `ACCEPTED` Work는 UNIQUE·복합
  FK·초기 상태 증거다. 장기 수락 흐름은 별도 생명주기 Test가 담당한다.

## HTTP 계약 기준선과 목표 공백

| Operation                | 현재 Characterization                                                                         | Target 판정                                                                                    |
| ------------------------ | --------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| Auth 가입·로그인·Session | Status와 공통 `data/error/meta`, Session·CSRF·역할 경계를 MockMvc와 Security 통합 Test로 고정 | 구현 확인 범위는 유지                                                                          |
| Work 생성·수정·삭제      | 생성 201, 수정·삭제 204, 소유권/상태 4xx, 현재 7필드 `dailyWage` 입력                         | WORK-008의 `hourlyWage` 서버 산정은 Partial이며 #285에서 구현하지 않음                         |
| 초대 발급·조회           | 최초 201/기존 200, Bearer Token, 현재 조회의 정확한 필드 집합                                 | `hourlyWage`, `healthCertificateRequired` 공백과 내부 `termsVersion` 노출은 INVITE-003 Partial |
| 초대 수락                | 200, 같은 Body Replay, `Idempotency-Replayed` Header, 0byte Body와 오류 Catalog               | 현재 Aggregate는 고정하되 재접속 UI Key 보존 공백은 담당 기능 이슈에 남김                      |
| 충전·출금                | 최초 201/Replay 200, Replay Header, 금액·계좌 오류와 공통 Envelope                            | 현재 구현된 WALLET 범위와 정합                                                                 |
| 정상 정산                | 200, OWNER 권한, 현재 전액 지급 Aggregate                                                     | SETTLE-004 legacy 기반. 지각·노쇼 계약과 혼동 금지                                             |
| 문서 파일                | 200, `Content-Type`, inline/attachment, Checksum·권한·404/403                                 | 문서 목록·공유의 Target 전체 필드 공백은 별도 기능 이슈                                        |

P0 Target와 현재 응답이 다르면 테스트를 삭제하거나 목표를 낮추지 않는다. 현재 테스트에는
`characterization/Partial` 의미를 남기고, 목표 계약과 담당 이슈는 Traceability에서 확인한다.

## 장시간 Work 생명주기

다음 경계가 하나의 Test Transaction이나 열린 Thread로 합쳐지면 실패다.

1. Context A에서 OWNER 기반 행을 준비하고 `WorkCaseService.create`로 DRAFT를 commit한다.
2. Clock을 전진시키고 Context B에서 `InvitationIssueService.issue`로 PENDING을 commit한다.
3. Context B를 닫아 Service 객체와 요청 상태를 폐기하고 Clock을 다시 전진시킨다.
4. Context C와 새 WORKER Principal로 Token을 재검증하고 수락 Aggregate를 commit한다.
5. Context C도 닫은 뒤 Context D에서 Work·Invitation·Contract·Document·Escrow·Ledger·
   Settlement·Claim을 재조회하고 같은 Key Replay가 자금을 다시 움직이지 않음을 확인한다.

이 Test는 사용자·사업장·초기 지갑만 SQL Fixture로 만든다. DRAFT, 초대, 수락 상태를 SQL로
만들면 장기 생명주기 검증으로 인정하지 않는다.

## Architecture Gate

`scripts/check-project-guardrails.js`는 비교 기준선에 이미 있던 위반을 안정적인 ID로 고정하고
신규 증가만 실패시킨다.

- 새 cross-module Mapper import
- 새 Controller→Mapper import
- 새 Domain→Spring/MyBatis/Web DTO·persistence import
- 새 Production hardcoded Mock
- 새 Mapper XML→API Response DTO `resultType` 또는 `resultMap type`

마지막 규칙은 아래 다섯 실명 타입의 동결 Registry, Controller가 import하는 DTO와
`*Response` 타입을 API 경계로 분류한다. 위반 ID에는 Mapper Tag 종류와 `id`를 포함하므로
같은 파일에서 같은 DTO를 쓰는 새 Statement도 신규 위반이다. Controller import가 Service
경계로 이동해도 동결 Registry는 사라지지 않는다. 현재 기준선은 새 사용을 정당화하지 않는다.

| Mapper                    | 기존 API 경계 DTO                          |
| ------------------------- | ------------------------------------------ |
| `BadgeQueryMapper.xml`    | `com.gighub.badge.dto.UserBadge`           |
| `DocumentQueryMapper.xml` | `com.gighub.document.dto.Document`         |
| `DocumentQueryMapper.xml` | `com.gighub.document.dto.DocumentVersion`  |
| `DocumentQueryMapper.xml` | `com.gighub.document.dto.DocumentListItem` |
| `DocumentQueryMapper.xml` | `com.gighub.document.dto.DocumentShare`    |

그 밖의 기존 Mapper·Controller·Production Mock 위반과 후속 소유자는
[`MODULE_BOUNDARIES.md`](MODULE_BOUNDARIES.md)의 `TV-*` 표를 따른다. 기준선 ID를 바꾸어
우회하거나 새 타입을 기존 DTO 이름에 숨기지 않는다.

## 실패 분류

| 분류                   | 처리                                                                                  |
| ---------------------- | ------------------------------------------------------------------------------------- |
| Refactoring regression | 현재 이슈에서 원인을 수정하고 같은 위험의 테스트를 유지한다.                          |
| Existing feature gap   | Traceability의 기존 기능 이슈에 연결하고 RF PR에서 구현하지 않는다.                   |
| Target mismatch        | 현재 Characterization과 목표 계약을 둘 다 보존하고 상태를 Partial/Blocked로 기록한다. |
| Environment failure    | 명령, 로컬 설정, DB/Flyway 상태와 실패 원문을 기록하고 성공으로 표시하지 않는다.      |

## 유지·통합·삭제 Review

| 후보                                                | 현재 판단                  | 재검토 시점과 근거                                                                    |
| --------------------------------------------------- | -------------------------- | ------------------------------------------------------------------------------------- |
| Funding/Withdrawal의 대칭 Idempotency·Snapshot Test | 유지                       | #289 이후 같은 Port·타입으로 실제 수렴하고 Operation별 회귀가 중복임을 증명할 때 통합 |
| Service의 희귀 malformed snapshot·update-count Test | 유지                       | #291이 impossible state를 타입으로 제거한 뒤 축소 검토                                |
| Work 상세의 Direct SQL 근태 Fixture                 | 유지·용도 명시             | Read Model 계약을 대체하는 실행 경로가 생길 때만 축소                                 |
| Idempotency DB umbrella Test                        | 유지, named Test 분리 후보 | #288 crash-window 계약 확정 뒤 실패 격리를 위해 분리                                  |
| Controller Status·Header·정확한 JSON 필드 Test      | 삭제 금지                  | API Target 이행 때 Characterization을 목표 계약으로 교체                              |
| 동시 자금·전체 Rollback·보존식·DB 제약 Test         | 삭제 금지                  | 구조가 바뀌어도 같은 R3 불변식을 다른 실행 증거로 유지                                |

## 로컬 DB 재현

DB 준비·Flyway·복구는 [`../runbooks/DATABASE_RUNBOOK.md`](../runbooks/DATABASE_RUNBOOK.md)를
따른다. 설정 파일은 Git에 넣지 않고 절대 경로로 전달한다.

```powershell
./backend/gradlew.bat -p backend `
  "-Dgighub.database.config=C:/absolute/path/to/database-local.properties" `
  databaseTest --tests "com.gighub.invitation.service.impl.LongLivedWorkLifecycleDatabaseIntegrationTest"
```

```powershell
./backend/gradlew.bat -p backend `
  "-Dgighub.database.config=C:/absolute/path/to/database-local.properties" `
  databaseTest
```

집중 실행 뒤 전체 `databaseTest`를 이슈 완료 경계에서 한 번 실행한다. 실패한 R3 Test를 기본
`test`가 통과했다는 이유로 무시하지 않는다.
