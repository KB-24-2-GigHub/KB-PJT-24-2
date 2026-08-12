# 로컬 데이터베이스 실행 Runbook

## 목적과 현재 기준

이 문서는 Docker Compose로 MySQL을 실행하고 Flyway Migration을 적용한 뒤 Spring·MyBatis 연결까지 확인하는 팀 공통 절차입니다. 로컬 개발 DB의 시작, 검증, 중지와 장애 대응에 사용합니다.

| 항목                | 현재 기준                           |
| ------------------- | ----------------------------------- |
| 문서 상태           | 현재 기준                           |
| Migration Head      | `202608112307`                      |
| Versioned Migration | 15개                                |
| 도메인 테이블       | 24개 (`flyway_schema_history` 제외) |
| MySQL               | `mysql:8.4.10`                      |
| Flyway CLI          | `flyway/flyway:12.9.0`              |
| MySQL Connector/J   | `9.7.0`                             |

실행 코드와 이 문서가 다르면 다음 원본을 우선합니다.

| 대상                                | 단일 원본                                                                                              |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------ |
| 컨테이너·포트·볼륨·Flyway 실행 설정 | 루트 `compose.yaml`                                                                                    |
| DB 스키마                           | 소유자가 작성·채택한 `backend/src/main/resources/db/migration/V*.sql`                                  |
| JDBC·MyBatis·트랜잭션 설정          | `backend/src/main/java/com/gighub/config/DatabaseConfig.java`                                          |
| DB 라이브러리 버전과 검증 작업      | `backend/build.gradle`                                                                                 |
| 스키마의 작업용 요약                | [`../agent/SCHEMA_OVERVIEW.md`](../agent/SCHEMA_OVERVIEW.md)                                           |
| 사람이 읽는 통합 DDL                | [`../database/schema-snapshot-202608112307.sql`](../database/schema-snapshot-202608112307.sql), 참고용 |

`V202607311427`부터 `V202608112307`까지는 PM·관리자 승인을 거친 현재 정식
Migration입니다. 통합 DDL은 같은 Head를 빈 DB에서 검토하기 위한 읽기용 Snapshot이며 기존
DB 업그레이드에는 반드시 Flyway Migration을 사용합니다.

Spring 애플리케이션은 Flyway를 자동 실행하지 않습니다. Migration은 별도 Flyway 컨테이너가 적용하며, 애플리케이션은 호스트에서 외부 설정 파일을 읽어 MySQL에 연결합니다.

```text
V*.sql ──> Flyway 컨테이너 ── db:3306 ──> MySQL 컨테이너 ──> mysql-data 볼륨
Spring/Tomcat(호스트) ── localhost:${MYSQL_PORT} ────────────────┘
```

### 시간 저장 규칙

- 시점을 뜻하는 DB 컬럼은 `DATETIME(6)`을 유지하며 `Asia/Seoul` 현지 시각으로 저장하고 해석합니다. `DATETIME` 자체에는 timezone 정보가 없습니다.
- API의 시점 필드는 UTC `Instant`(`2026-07-31T09:00:00Z`)로 주고받고, 서버 경계에서 `Asia/Seoul` DB 값과 변환합니다.
- 달력 날짜 자체가 의미인 DB `DATE`는 Java `LocalDate`로 처리합니다.
- DB, JDBC 또는 컨테이너 timezone을 바꾸면 같은 `DATETIME(6)` 값의 의미가 달라질 수 있으므로 Migration이 아니라 별도의 데이터 변환 계획과 검증이 필요합니다.

## 사전 조건

- Docker Desktop의 Linux 컨테이너 엔진이 실행 중이어야 합니다.
- JDK 17을 사용합니다.
- Node.js와 npm을 사용합니다.
- 최초 실행 시 Docker 이미지와 Gradle 의존성을 내려받을 네트워크 또는 이미 준비된 로컬 캐시가 필요합니다.
- 모든 명령은 저장소 루트의 PowerShell에서 실행합니다.
- 실제 비밀번호는 `.env`와 `backend/config/database-local.properties`에만 두고 Git에 추가하지 않습니다.

## 최초 1회 준비

로컬 파일이 없을 때만 예제를 복사합니다.

```powershell
Copy-Item .env.example .env
Copy-Item backend/config/database.example.properties backend/config/database-local.properties
```

두 파일에서 다음 값을 서로 맞춥니다.

| `.env`           | `database-local.properties`  |
| ---------------- | ---------------------------- |
| `MYSQL_DATABASE` | JDBC URL의 데이터베이스 이름 |
| `MYSQL_USER`     | JDBC 사용자                  |
| `MYSQL_PASSWORD` | JDBC 비밀번호                |
| `MYSQL_PORT`     | JDBC URL의 `localhost` 포트  |

호스트 애플리케이션의 JDBC 주소는 `localhost:${MYSQL_PORT}`를 사용하지만, Docker 네트워크 안의 Flyway는 항상 `db:3306`으로 연결합니다.

### 초대 Link 설정

같은 `database-local.properties`가 초대 Link 설정도 함께 담습니다. 아래 세 키가 없으면 Spring Root Context가 생성되지 않아 애플리케이션과 `databaseTest`가 모두 시작하지 못합니다.

| 키                            | 값                                                                    |
| ----------------------------- | --------------------------------------------------------------------- |
| `invite.hmac.secret`          | 초대 Token 파생 Secret. 32자 이상이며 저장소에 커밋하지 않습니다.     |
| `invite.hmac.previous-secret` | Secret 교체 중에만 이전 값을 넣고, 평소에는 빈 값으로 둡니다.         |
| `invite.web-origin`           | 초대 URL을 만들 절대 Origin. 경로·Query·Fragment를 포함하지 않습니다. |

로컬 기본값은 예제 파일에 있습니다. `invite.web-origin`은 로컬 Vite 주소인 `http://localhost:5173`을 사용합니다.

Secret을 교체할 때는 새 값을 `invite.hmac.secret`에, 직전 값을 `invite.hmac.previous-secret`에 둡니다. 이전 Secret으로 발급된 활성 초대는 만료되거나 철회될 때까지 Link를 다시 만들어 낼 수 있어야 하므로, 그 초대들이 모두 끝난 뒤에만 `invite.hmac.previous-secret`을 비웁니다. 두 값을 동시에 바꾸면 아직 유효한 초대의 현재 Link를 조회할 수 없게 되고, OWNER는 재발급으로만 복구할 수 있습니다.

### 출퇴근 고정 QR 서명 키

같은 `database-local.properties`가 사업장 고정 QR 서명 키도 함께 담습니다. 아래 두 키가 없으면 Spring Root Context가 생성되지 않아 애플리케이션과 `databaseTest`가 모두 시작하지 못합니다.

| 키                      | 값                                                                             |
| ----------------------- | ------------------------------------------------------------------------------ |
| `qr.hmac.active-key-id` | 새 QR을 서명할 키 식별자. 1~16자의 영숫자, `-`, `_`만 사용합니다.              |
| `qr.hmac.key.<식별자>`  | 그 식별자의 서명 키. Base64로 디코딩해 32바이트 이상이며 커밋하지 않습니다.    |

로컬 값은 아무 임의 값이면 되고 팀원끼리 맞출 필요가 없습니다. 각자 자기 DB의 QR만 검증하기 때문입니다.

```powershell
[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }))
```

키 교체 절차는 초대 Secret과 다릅니다. 초대 Token은 만료되므로 이전 Secret 하나를 함께 두면 충분하지만, 고정 QR은 만료되지 않고 인쇄되어 매장에 부착됩니다. 그래서 Token이 서명에 쓴 키 식별자를 함께 담고, 현장에 남아 있는 모든 식별자가 등록되어 있어야 합니다. 교체 중에는 `qr.hmac.key-ids`에 새 식별자와 구 식별자를 함께 적고 각 키 값을 모두 둡니다.

```properties
qr.hmac.active-key-id=k2
qr.hmac.key-ids=k2,k1
qr.hmac.key.k2=<새 값>
qr.hmac.key.k1=<이전 값>
```

교체가 아닐 때는 `qr.hmac.key-ids`를 생략합니다. 활성 키 하나만 등록됩니다. 구 식별자를 목록에서 지우면 그 키로 서명된 인쇄물이 그 시점부터 동작하지 않으므로, 해당 사업장들이 새 QR을 재출력해 교체한 뒤에만 지웁니다.

새 clone, Connector/J 버전 변경 또는 Gradle `clean` 실행 후에는 Flyway 컨테이너가 마운트할 JDBC Driver를 먼저 준비합니다.

```powershell
.\backend\gradlew.bat -p backend prepareFlywayDriver
```

정상 완료되면 다음 파일이 생성됩니다.

```text
backend/build/flyway-drivers/mysql-connector-j-9.7.0.jar
```

현재 `npm run db:migrate`에는 Driver 준비 작업이 포함되어 있지 않으므로 이 선행 단계를 생략하지 않습니다.

## 표준 시작과 Migration

### 1. Compose 설정 확인

```powershell
docker compose config --quiet
```

출력이 없이 종료 코드가 `0`이면 정상입니다.

### 2. MySQL 시작

```powershell
docker compose up -d db
docker compose ps
```

`db` 서비스가 `healthy`가 될 때까지 기다립니다.

### 3. 적용 전 상태 확인

```powershell
docker compose --profile tools run --rm flyway info
```

빈 DB라면 Migration이 `Pending`, 이미 적용한 DB라면 `Success`로 표시됩니다.

### 4. Migration 적용

```powershell
npm.cmd run db:migrate
```

현재 다음 열다섯 개 Migration이 순서대로 적용되어야 합니다.

| Version        | 파일                                                         |
| -------------- | ------------------------------------------------------------ |
| `202607200001` | `V202607200001__create_gig_hub_baseline.sql`                 |
| `202607211440` | `V202607211440__add_signup_and_workplace_schema.sql`         |
| `202607221300` | `V202607221300__support_contract_escrow_test_flow.sql`       |
| `202607301027` | `V202607301027__remove_invited_from_work_case_status.sql`    |
| `202607301152` | `V202607301152__split_workplace_address.sql`                 |
| `202607311427` | `V202607311427__move_qr_tokens_to_workplace_scope.sql`       |
| `202607311428` | `V202607311428__add_password_reset_tokens.sql`               |
| `202607311429` | `V202607311429__add_check_out_missing_work_case_status.sql`  |
| `202608041138` | `V202608041138__remove_employer_profiles.sql`                |
| `202608041614` | `V202608041614__add_idempotency_request_claims.sql`          |
| `202608051337` | `V202608051337__replace_mock_bank_account_user_with_pin.sql` |
| `202608061428` | `V202608061428__add_document_access_audit_details.sql`       |
| `202608111743` | `V202608111743__add_document_access_audit_allowlists.sql`    |
| `202608111744` | `V202608111744__add_user_badge_type_allowlist.sql`           |
| `202608112307` | `V202608112307__add_settlement_retry_and_dispute_title.sql`  |

같은 명령을 다시 실행했을 때 `Schema ... is up to date. No migration necessary.`가 나오면 반복 실행도 정상입니다.

#### `202607311427` 적용 전 확인

QR Migration은 기존 근무·동작별 QR을 사업장 고정 QR 구조로 전환합니다.

- 기존 QR 발급자가 해당 근무 사업장의 소유자와 다른 행이 하나라도 있으면 Migration이 중단됩니다. 먼저 읽기 전용 점검 SQL로 불일치를 확인하고 원인을 소유자에게 보고합니다. 보정 여부와 후속 Migration 범위는 소유자가 승인하며, 작성은 그 범위에 명시적으로 지정된 관리자 또는 현재 에이전트만 수행합니다.
- 기존 `ACTIVE` QR은 모두 `REVOKED` 처리되고, 적용 시점의 `ACTIVE` 사업장마다 새 nonce 기반 QR이 하나 생성됩니다.
- 기존 행은 `legacy_*` 컬럼으로 보존되며 새 고정 QR로 다시 활성화할 수 없습니다.
- 새 QR 문자열은 DB nonce 원문만 노출하지 않고, 애플리케이션이 외부 설정 HMAC Key로 nonce와 사업장 ID를 서명해야 합니다. 이 Migration은 QR API나 HMAC 설정을 구현하지 않습니다.
- 운영 데이터에 적용할 때는 새 QR 발급·조회·스캔 API 배포 및 사장님 재출력 안내와 순서를 맞춰야 합니다.

비밀번호 재설정 Migration은 Hash 저장소만 추가합니다. Token 원문 생성·전달·만료
처리·단일 사용 API가 구현되기 전에는 이 테이블이 있어도 비밀번호 재설정 기능이 동작하지
않습니다.

#### `202608041138` 적용 전 확인

이 Migration은 M2에서 사용하지 않는 `employer_profiles`를 제거합니다.

- `business_name`, `contact_phone`, `default_workplace_address`를 다른 컬럼으로 옮기거나 이름을
  바꾸지 않고 테이블과 함께 삭제합니다.
- OWNER 식별정보는 `users`, 사업체·사업장 기준정보와 공개 전화번호는 `workplaces`를
  사용합니다. `users.phone`과 `workplaces.phone`은 서로 독립된 값입니다.
- 일회용 또는 폐기 가능한 로컬 DB가 아닌 곳에 적용하려면 관리자가 기존 행의 보존 필요성을
  먼저 확인하고, 필요하면 Migration 실행과 분리된 승인된 추출·보관 절차를 준비해야 합니다.
- 이 저장소 작업에서는 공유·Staging·Production DB에 Migration을 적용하지 않습니다.

#### `202608041614` 적용 후 확인

이 Migration은 사용자·Operation·Key 범위의 멱등 요청 Claim을 저장하는
`idempotency_requests` 테이블만 추가합니다.

- 기존 충전·출금·지갑 원장의 테이블과 전역 `idempotency_key` UNIQUE 제약은 유지합니다.
- 신규 테이블은 `(user_id, operation_code, idempotency_key)`를 한 번만 허용합니다.
- 별도의 상태·만료·Fingerprint 보조 Index, 기존 데이터 Backfill과 Cleanup Scheduler는
  포함하지 않습니다.
- Claim 선점, 성공 결과 저장·재응답, 동시 요청 409와 중단 복구는 후속 애플리케이션 구현
  범위입니다.

#### `202608051337` 적용 전·후 확인

이 Migration은 기존 Mock 계좌의 사용자 귀속만 제거하고 Demo PIN을 추가합니다.

- `fk_mock_bank_accounts_user`, `idx_mock_bank_accounts_user_status`,
  `mock_bank_accounts.user_id`를 제거합니다.
- `pin CHAR(4) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '0000'`과
  숫자 네 자리 `ck_mock_bank_accounts_pin`을 추가합니다. 상수 기본값이 기존 행도
  `0000`으로 채우므로 감사 시각을 바꾸는 별도 `UPDATE`는 실행하지 않습니다.
- 계좌 ID·식별자·통화·잔액·가용액·상태·시각과 기존 Unique·금액/통화/상태 CHECK를
  유지합니다.
- `funding_orders.linked_account_id`, `withdrawal_requests.linked_account_id`,
  `mock_bank_transactions.account_id`의 계좌 참조와 기존 데이터는 유지합니다.
- `(bank_code, mock_account_number)` Unique가 은행 코드 선두 조회를 지원하므로 별도
  중복 Index를 추가하지 않습니다.

기존 Head 업그레이드 검증에서는 적용 전에 계좌와 세 참조의 ID·금액·상태·시각을 기록하고,
적용 뒤 같은 값을 비교합니다. 다음 조회 결과는 `0`이어야 합니다.

```sql
SELECT COUNT(*) AS invalid_pin_rows
FROM mock_bank_accounts
WHERE pin IS NULL OR pin <> '0000';
```

세 참조 FK는 다음 조회에서 그대로 나타나야 합니다.

```sql
SELECT table_name, constraint_name, column_name,
       referenced_table_name, referenced_column_name
FROM information_schema.key_column_usage
WHERE constraint_schema = DATABASE()
  AND referenced_table_name = 'mock_bank_accounts'
ORDER BY table_name, constraint_name;
```

이 Migration은 사용자 비귀속 계좌를 사용하는 FE·BE 흐름을 구현하지 않습니다. 기존
사용자 소유 계좌 Mapper와 Gateway를 새 조회·PIN 계약으로 바꾸는 작업은 호환 Backend
릴리스와 함께 적용해야 합니다. 이 저장소 작업에서는 격리된 Disposable DB만 업그레이드하며
공유·Staging·Production DB에는 적용하지 않습니다.

#### `202608061428` 적용 전·후 확인

이 Migration은 `document_access_logs`에 문서 Version과 구조화된 거부 사유를 추가합니다.

- `document_version_id`는 NULL을 허용하지만, 값이 있으면 `(document_id,
document_version_id)` 복합 FK로 같은 문서의 Version만 참조할 수 있습니다.
- `denial_reason`은 ASCII 대소문자를 구분하는 `VARCHAR(50)`이며, 값이 있으면 결과가
  `DENIED`이고 빈 문자열이 아니어야 합니다.
- 기존 감사 행은 당시 Version과 거부 사유를 안전하게 복원할 수 없으므로 Backfill하지
  않습니다. 따라서 두 신규 컬럼의 기존 값이 NULL인 것은 정상입니다.
- 호환 Backend는 새로 기록하는 허용 접근에 확정 Version을 저장하고, 문서를 찾은 뒤 거부한
  접근에는 가능한 Version과 구조화된 거부 사유를 함께 저장해야 합니다.

기존 Head 업그레이드 전후에는 감사 행 수가 같아야 합니다. 다음 조회의 `invalid_rows`는
`0`이어야 합니다.

```sql
SELECT COUNT(*) AS invalid_rows
FROM document_access_logs
WHERE denial_reason IS NOT NULL
  AND (result <> 'DENIED' OR CHAR_LENGTH(denial_reason) = 0);
```

#### `202608111743`·`202608111744` 적용 전 확인

두 Migration은 새 컬럼·Index를 만들지 않고 기존 코드성 문자열 컬럼에 유한값 CHECK만
추가합니다.

- `202608111743`의 단일 `ALTER TABLE document_access_logs`는 `action`을
  `HEALTH_CERT_FILE_VIEW`, `HEALTH_CERT_FILE_DOWNLOAD`,
  `CONTRACT_FILE_VIEW`, `CONTRACT_FILE_DOWNLOAD`, `DOCUMENT_DETAIL_VIEW`만 허용합니다.
- `ck_document_access_logs_denial_reason`은 기존 "빈 문자열이 아님" 조건을 승인된 다섯 사유
  목록으로 대체합니다. 기존 Migration 파일은 수정하지 않고 새 Migration에서 제약만 교체합니다.
- `202608111744`의 단일 `ALTER TABLE user_badges`는 `badge_type`을 `TRUST_OWNER`와
  `TRUST_WORKER`만 허용합니다.

MySQL은 서로 다른 `ALTER TABLE` 문을 하나의 Transaction으로 묶지 않습니다. 그래서 두 테이블을
한 Flyway Version에 넣지 않고 한 DDL 문씩 연속 Version으로 분리했습니다. 각 문장 안의 제약 교체는
원자적으로 성공하거나 실패하고, 앞 Version이 성공한 뒤 다음 Version이 실패하면 성공 이력은 그대로
남습니다. Flyway는 실패한 단계만 점검·복구한 뒤 재실행합니다.

적용 전에 목록 밖의 값이 남아 있으면 해당 `ALTER TABLE`이 실패합니다. 두 Version 중 하나라도
실행하기 전에 다음 두 조회 결과가 모두 `0`인지 확인하고, `0`이 아니면 임의 보정하지 않고 범위를
소유자에게 보고합니다.

```sql
SELECT COUNT(*) AS invalid_audit_rows
FROM document_access_logs
WHERE action NOT IN (
        'HEALTH_CERT_FILE_VIEW', 'HEALTH_CERT_FILE_DOWNLOAD',
        'CONTRACT_FILE_VIEW', 'CONTRACT_FILE_DOWNLOAD', 'DOCUMENT_DETAIL_VIEW'
      )
   OR (denial_reason IS NOT NULL
       AND denial_reason NOT IN (
           'PARTY_ACCESS_DENIED', 'DOCUMENT_UNAVAILABLE', 'FILE_UNAVAILABLE',
           'CHECKSUM_MISMATCH', 'SIGNED_VERSION_UNAVAILABLE'
       ));

SELECT COUNT(*) AS invalid_badge_rows
FROM user_badges
WHERE badge_type NOT IN ('TRUST_OWNER', 'TRUST_WORKER');
```

`action`과 `badge_type`은 테이블 기본 `utf8mb4_0900_ai_ci`이므로 CHECK 비교가 대소문자를
구분하지 않습니다. `denial_reason`은 `ascii_bin`이라 대소문자를 구분합니다. 애플리케이션은 세
컬럼 모두 대문자 상수만 기록합니다.

##### 두 Version 사이 실패 복구

다음 절차는 공유·Staging·Production이 아니라 폐기 가능한 로컬 DB에서만 수행합니다. 실패한
Migration 파일을 수정하거나 이미 성공한 `202608111743`을 수동으로 되돌리지 않습니다.

1. `flyway info`와 Schema History에서 어느 Version이 성공·실패·대기 상태인지 확인합니다.

   ```powershell
   docker compose --profile tools run --rm flyway info
   ```

   ```sql
   SELECT installed_rank, version, description, success
   FROM flyway_schema_history
   WHERE version IN ('202608111743', '202608111744')
   ORDER BY installed_rank;
   ```

2. 이름만 보지 말고 실제 CHECK 식까지 확인합니다.

   ```sql
   SHOW CREATE TABLE document_access_logs;
   SHOW CREATE TABLE user_badges;
   ```

   `202608111743` 실패 시에는 신규 action CHECK가 없어야 하고 기존 denial CHECK가 유지되어야
   합니다. `202608111744` 실패 시에는 두 감사 CHECK가 적용된 `202608111743`만 성공 상태이고
   뱃지 CHECK는 없어야 합니다. 이 상태와 다르면 수동 DDL을 실행하지 말고 소유자에게 보고해
   후속 immutable Migration 범위를 결정합니다.

3. 목록 밖 데이터가 원인이면 소유자가 승인한 별도 데이터 처리로 원인을 제거합니다. 이 Runbook은
   실제 행의 의미를 추정하는 `UPDATE`나 `DELETE`를 제공하지 않습니다.
4. 실패한 문장이 테이블에 일부 적용되지 않았음을 확인한 뒤, Flyway의 실패 이력만 정리합니다.

   ```powershell
   docker compose --profile tools run --rm flyway repair
   ```

   `repair`는 Schema를 고치지 않으므로 2단계 확인 전에 실행하지 않습니다. 실패 이력이 없고
   `info`가 해당 Version을 `Pending`으로 표시하면 불필요한 `repair`는 생략합니다.
5. `npm.cmd run db:migrate`를 다시 실행합니다. 성공한 앞 Version은 재실행하지 않고 실패했던
   Version부터 적용되는지 확인한 뒤 `validate`와 `info`를 실행합니다.

#### `202608112307` 정산 생명주기 적용 전 확인

이 Migration은 `settlements`의 환불 종료·Scheduler 재시도 감사와 `disputes.title`만
추가합니다. 범용 `idempotency_requests` Claim과 기존
`idx_settlements_status_due_at(status, due_at)`은 그대로 사용합니다.

다음 count는 모두 `0`이어야 합니다. ID·금액·내용은 출력하지 않습니다. 하나라도 0이 아니면
상태나 제목을 추정해 `UPDATE`하지 말고 수동 대사 대상으로 분리합니다.

```sql
SELECT COUNT(*) AS stuck_processing FROM settlements WHERE status = 'PROCESSING';
SELECT COUNT(*) AS legacy_failed FROM settlements WHERE status = 'FAILED';
SELECT COUNT(*) AS legacy_disputes_without_title FROM disputes;

-- COMPLETED마다 RELEASED Escrow와 OWNER·WORKER ESCROW_RELEASE 원장 한 쌍이 정확해야 한다.
SELECT COUNT(*) AS completed_settlement_fund_integrity
FROM settlements s
JOIN work_cases wc ON wc.id = s.work_case_id
LEFT JOIN escrows e ON e.work_case_id = s.work_case_id
WHERE s.status = 'COMPLETED'
  AND (
    wc.employer_id = wc.worker_id OR wc.agreed_wage <> s.amount
    OR e.id IS NULL OR e.status <> 'RELEASED' OR e.amount <> s.amount
    OR (SELECT COUNT(*) FROM wallet_transactions wt
        WHERE wt.work_case_id = s.work_case_id
          AND wt.transaction_type = 'ESCROW_RELEASE'
          AND wt.amount = s.amount AND wt.reference_type = 'ESCROW'
          AND wt.reference_id = e.id) <> 2
    OR (SELECT COUNT(*) FROM wallet_transactions wt
        JOIN wallets w ON w.id = wt.wallet_id
        WHERE wt.work_case_id = s.work_case_id AND w.user_id = wc.employer_id
          AND wt.transaction_type = 'ESCROW_RELEASE'
          AND wt.amount = s.amount AND wt.reference_type = 'ESCROW'
          AND wt.reference_id = e.id
          AND wt.available_before = wt.available_after
          AND wt.locked_before >= wt.amount
          AND wt.locked_after = wt.locked_before - wt.amount) <> 1
    OR (SELECT COUNT(*) FROM wallet_transactions wt
        JOIN wallets w ON w.id = wt.wallet_id
        WHERE wt.work_case_id = s.work_case_id AND w.user_id = wc.worker_id
          AND wt.transaction_type = 'ESCROW_RELEASE'
          AND wt.amount = s.amount AND wt.reference_type = 'ESCROW'
          AND wt.reference_id = e.id
          AND wt.available_after >= wt.available_before
          AND wt.available_after - wt.available_before = wt.amount
          AND wt.locked_before = wt.locked_after) <> 1
  );

SELECT COUNT(*) AS invalid_state_shape
FROM settlements
WHERE NOT (
    (status = 'WAITING' AND approved_by_user_id IS NULL AND due_at IS NULL
        AND processing_at IS NULL AND completed_at IS NULL AND failure_code IS NULL)
    OR (status IN ('SCHEDULED', 'ON_HOLD') AND approved_by_user_id IS NULL
        AND due_at IS NOT NULL AND processing_at IS NULL AND completed_at IS NULL
        AND failure_code IS NULL)
    OR (status = 'PROCESSING' AND processing_at IS NOT NULL AND completed_at IS NULL
        AND failure_code IS NULL AND (due_at IS NOT NULL OR approved_by_user_id IS NOT NULL))
    OR (status = 'COMPLETED' AND due_at IS NOT NULL AND processing_at IS NOT NULL
        AND completed_at IS NOT NULL AND failure_code IS NULL)
    OR (status = 'FAILED' AND approved_by_user_id IS NULL AND due_at IS NOT NULL
        AND processing_at IS NULL AND completed_at IS NULL AND failure_code IS NOT NULL)
);
```

Migration은 같은 테이블의 변경을 한 `ALTER TABLE`로 묶습니다. `settlements`는 재시도 세 컬럼,
기존 상태 CHECK 제거와 새 생명주기 CHECK 추가가 함께 성공하거나 함께 실패합니다.
`disputes`도 제목 컬럼과 제목 CHECK가 함께 성공하거나 함께 실패합니다.

두 테이블 사이에서는 비원자 부분 실패가 가능합니다. `disputes` 단계가 실패하면 다음 구조가
정상적인 복구 시작점입니다.

- `settlements`: 재시도 컬럼 세 개와 `ck_settlements_lifecycle`이 모두 존재하고 기존
  `ck_settlements_status`는 없습니다.
- `disputes`: `title`과 `ck_disputes_title`이 모두 없습니다.
- Flyway History: `202608112307` 실패 한 건입니다.

이 조합과 다르면 `repair`하지 말고 소유자에게 보고합니다. 정상 조합이면 실패 원인을 제거한 뒤
`flyway repair`로 실패 이력만 정리하고 재실행합니다. 재실행은 완전히 적용된 `settlements`를
건너뛰고 `disputes`만 적용합니다. 기존 disputes 행에는 원래 제목을 복원할 근거가 없으므로,
데이터 처리 승인이 별도로 없는 한 이 Migration을 적용하지 않습니다.

완전 적용 판정은 이름 존재만 보지 않습니다. 재시도 컬럼의 unsigned·NULL·기본값·`datetime(6)`,
제목의 `varchar(100) NOT NULL`, 각 제약의 `CHECK` 유형과 MySQL 8.4.10이 저장한 CHECK clause
hash가 모두 일치해야 합니다. nullable `INT retry_count` 같은 수동 부분 구조나 같은 이름의 다른
CHECK는 자동 복구하지 않고 preflight에서 차단합니다.

적용 후 충분한 수의 합성 `SCHEDULED` 행을 넣고 후보 조회 Index의 자연 실행 계획을 확인합니다.
`FORCE INDEX` 없이도 `key`가 `idx_settlements_status_due_at`이고 `type`이 `range`여야 합니다.
`due_at ASC, id ASC` 정렬의 `id`는 현재 Index에 없으므로 `Using filesort`는 허용합니다. 후보가
최대 100건이라 별도 중복 Index를 추가하지 않습니다. `FORCE INDEX`는 Optimizer 비교 진단에만
사용하고 Index 충분성의 성공 근거로 사용하지 않습니다.

```sql
EXPLAIN
SELECT id
FROM settlements
WHERE status = 'SCHEDULED'
  AND due_at <= NOW(6)
  AND (next_retry_at IS NULL OR next_retry_at <= NOW(6))
ORDER BY due_at ASC, id ASC
LIMIT 100;
```

`202608112307` 검증에서는 MySQL 8.4.10 폐기 DB의 합성 후보 2,000건(실제 후보 200건)으로
자연 계획이 `type=range`, `key=idx_settlements_status_due_at`, `rows=200`,
`Extra=Using index condition; Using where`를 선택했습니다. 이 계획에는 `Using filesort`가 없었고
별도 중복 Index도 추가하지 않았습니다.

빈 DB, Head `202608111744`의 승인 상태 행 Upgrade, 같은 명령 반복, 의도적인 두 번째 테이블
실패 뒤 `repair`·재실행을 폐기 가능한 MySQL에서 검증합니다. 공유·Staging·Production에는 이
절차를 실행하지 않습니다.

#### 현재 DDL과 제품 Workflow 경계

Head `202608112307`은 문서 접근 감사의 Version·거부 사유와 그 승인 목록, 뱃지 유형 목록,
정산 환불·재시도 생명주기와 분쟁 제목을
고정하며, 사용자 귀속 없는 Mock 계좌와 Demo PIN 구조, 독립된 멱등 요청 Claim 저장소,
`employer_profiles` 제거와 `CHECK_OUT_MISSING` 상태·근로자 필수 제약도 유지합니다. 이는 구조를
저장할 수 있다는 DDL 사실이며 각 Workflow의 Runtime 구현 완료를 뜻하지 않습니다.

| 기능                 | 현재 DDL                                                                                      | 승인된 제품 Workflow·후속 사항                                                                        |
| -------------------- | --------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| 퇴근 누락 상태       | `CHECK_OUT_MISSING` 허용, 해당 상태의 `worker_id` 필수. `attendance_records.result` 변경 없음 | 판정 시점·실행 주체·늦은 QR·보정·정산·장기 미해결 임금·기존 행 처리와 실제 조회에 맞춘 Scheduler Index |
| 100m 고정 반경       | 반경 기본값은 100이지만 두 반경 CHECK는 모든 양수를 허용                                      | 애플리케이션 강제로 충분한지, DB CHECK도 정확히 100으로 바꿀지 결정                                    |
| 시스템 생성 계약서   | `EMPLOYMENT_CONTRACT`도 `work_case_id=NULL` 허용                                              | 근무 건 필수 연결을 DB에서도 강제할지 결정                                                             |
| 계약서 3년 자동 삭제 | `documents.status=DELETED`는 있으나 전용 보존·완료·재시도 컬럼과 Index 없음                    | 7.0.0은 `ends_at` 서울 날짜+3년, 02:00 Keyset Job, DB 선삭제, Object 멱등 삭제와 Metadata·감사 무기한 보존을 확정. #131이 추가 DDL 없이 Runtime 구현 |
| 문서 접근 감사       | 문서와 선택적 Version, 승인 목록으로 제한된 행위·결과·거부 사유 저장. 기존 행의 신규 상세는 NULL | 호환 Backend가 새 접근마다 Version과 거부 사유를 빠짐없이 기록하고 보관·조회 정책을 적용               |
| 신뢰 뱃지            | `badge_type`은 두 종류만 허용하고 등급·건수·문턱은 `evidence` JSON에만 존재                   | 7.0.0은 누적 문턱, 사용자 잠금 뒤 재계산·Upsert, 닫힌 evidence와 별도 Backfill 없음을 확정. #182가 신규 Column·History 없이 Runtime 구현 |
| 멱등 요청 Claim      | 사용자·Operation·Key 복합 UNIQUE, Fingerprint와 성공 응답 Snapshot 저장                       | Claim 선점·Replay·즉시 409·중단 복구·만료 정리는 후속 애플리케이션 구현                                |
| 정산 재시도·환불     | `REFUNDED`, 재시도 감사 필드와 상태별 시각·승인자 결합 CHECK, 기존 `(status,due_at)` Index     | #172·#174·#175와 RF-09가 Scheduler·환불·분쟁 Runtime을 구현; Schema만으로 기능 완료 아님                 |
| 비귀속 Mock 계좌     | 사용자 FK 없이 숫자 네 자리 PIN 저장, 기존 주문·출금·은행 원장 계좌 참조 유지                 | 호환 Backend가 은행·계좌번호로 ACTIVE 계좌를 찾고 충전에만 PIN을 검증하도록 전환                       |

퇴근 누락 상태의 판정 시점·실행 주체, 늦은 퇴근·보정·정산 정책과 기존 `IN_PROGRESS`
데이터 처리는 여전히 미정입니다. 이 Workflow가 확정되기 전에는 Scheduler, 해소 API,
Backfill이나 Scheduler 전용 Index를 현재 DDL만 보고 구현하지 않습니다. 계약서 자동 삭제는
기준일과 삭제 범위를 확정한 뒤 Schema 보강 여부를 판단합니다.

소유자가 후속 Migration을 만든 뒤 이 Runbook의 Head·개수·파일 목록, `SCHEMA_OVERVIEW.md`,
ERD와 통합 DDL Snapshot을 같은 변경에서 갱신하고 빈 DB와 기존 Head Upgrade를 모두
검증합니다.

### 5. 이력과 파일 검증

```powershell
docker compose --profile tools run --rm flyway validate
docker compose --profile tools run --rm flyway info
```

현재 기준의 정상 결과는 열다섯 개 Migration의 검증 성공, Schema version `202608112307`, 모든
항목의 `Success`입니다.

## Spring·MyBatis 연결 검증

절대경로를 자신의 저장소 위치로 바꾸고 `/` 구분자를 사용합니다. 경로에 공백이 있으므로 JVM 인수 전체를 큰따옴표로 감쌉니다.

스키마 릴리스가 호환 애플리케이션보다 먼저 적용되는 경계에서는 먼저 스키마 전용 테스트를
실행합니다.

```powershell
.\backend\gradlew.bat -p backend "-Dgighub.database.config=C:/absolute/path/to/KB PJT/backend/config/database-local.properties" databaseTest --tests "com.gighub.bank.MockBankAccountPinSchemaDatabaseIntegrationTest"
.\backend\gradlew.bat -p backend "-Dgighub.database.config=C:/absolute/path/to/KB PJT/backend/config/database-local.properties" databaseTest --tests "com.gighub.document.DocumentAccessAuditSchemaDatabaseIntegrationTest"
.\backend\gradlew.bat -p backend "-Dgighub.database.config=C:/absolute/path/to/KB PJT/backend/config/database-local.properties" databaseTest --tests "com.gighub.badge.UserBadgeTypeSchemaDatabaseIntegrationTest"
.\backend\gradlew.bat -p backend "-Dgighub.database.config=C:/absolute/path/to/KB PJT/backend/config/database-local.properties" databaseTest --tests "com.gighub.document.DocumentShareUniquenessSchemaDatabaseIntegrationTest"
.\backend\gradlew.bat -p backend "-Dgighub.database.config=C:/absolute/path/to/KB PJT/backend/config/database-local.properties" databaseTest --tests "com.gighub.settlement.SettlementLifecycleSchemaDatabaseIntegrationTest"
```

호환 Mapper와 Service까지 같은 브랜치에 있으면 전체 DB 통합 테스트를 실행합니다.

```powershell
.\backend\gradlew.bat -p backend "-Dgighub.database.config=C:/absolute/path/to/KB PJT/backend/config/database-local.properties" databaseTest
```

연결 확인 테스트에서 예제의 기본 DB 이름을 사용한 정상 출력 형식은 다음과 같습니다. DB 이름을 변경했다면 첫 줄의 이름도 달라지며, `users` 행 수는 로컬 데이터에 따라 달라집니다.

```text
Connected database: kb_pjt, users table rows: N
BUILD SUCCESSFUL
```

일반 `npm run check`와 백엔드 기본 `test`는 `@Tag("database")` 테스트를 제외합니다.
`databaseTest`는 연결 확인 하나만이 아니라 현재 `database` Tag가 붙은 스키마, Mapper와 Service
통합 테스트 전체를 실행하므로 로컬 DB 상태와 필요한 합성 Fixture를 먼저 확인합니다.

### 실제 Tomcat 실행

WAR를 실행하는 Tomcat에도 같은 설정 파일 경로가 필요합니다. IntelliJ의 Tomcat Run/Debug Configuration 또는 사용하는 Tomcat 실행 스크립트의 JVM 옵션에 다음 값을 추가합니다.

```text
-Dgighub.database.config="C:/absolute/path/to/KB PJT/backend/config/database-local.properties"
```

이 속성에는 비밀번호가 아니라 로컬 설정 파일의 절대경로만 넣습니다. 속성이 없거나 파일을 읽지 못하면 Spring Root Context가 생성되지 않아 애플리케이션이 시작되지 않습니다.

## 선택적 계약·에스크로 Seed

계약·에스크로 테스트 시나리오가 필요할 때만 다음 명령을 실행합니다.

```powershell
npm.cmd run db:seed:contract
```

이 명령은 미적용 Migration을 먼저 적용한 뒤 [`test-contract-escrow.sql`](../../backend/src/test/resources/db/seed/test-contract-escrow.sql)을 실행합니다. 로컬 DB의 합성 테스트 데이터만 대상으로 하며 공용 DB나 운영 DB에서는 실행하지 않습니다.

재실행하면 아래 사용자 비귀속 Mock 계좌를 초기 상태로 되돌리기 위해 해당 계좌를 참조하는 로컬 `funding_orders`, `withdrawal_requests`, `mock_bank_transactions` 합성 기록을 먼저 정리합니다. 수동 테스트 이력을 보존해야 한다면 별도 로컬 DB나 Docker volume에서 Seed를 실행합니다.

| 항목           | 초기 상태                                        |
| -------------- | ------------------------------------------------ |
| 사장님 로그인  | `test_owner_17` / `Test1234!`                    |
| 근로자 로그인  | `test_worker_17` / `Test1234!`                   |
| 근무·일급      | 2026-08-01 09:00~18:00, 무급 휴게 60분·300,000원 |
| 사장님 지갑    | 가용 700,000원, 잠금 300,000원                   |
| 근로자 지갑    | 가용 0원, 에스크로 확보액 300,000원              |
| Mock 계좌      | 사용자 비귀속 합성 계좌, Demo PIN `0000`         |
| 업무 처리 상태 | 근무 `ACCEPTED`, 에스크로 `HELD`, 정산 `WAITING` |

지갑 충전·출금 테스트에는 다음 합성 계좌를 사용합니다. `accountNo`는 하이픈 없이 요청하고,
모든 계좌는 `ACTIVE` 상태와 Demo PIN `0000`으로 초기화됩니다.

| `bankCode` | 은행명      | `accountNo`      | 초기 잔액   |
| ---------- | ----------- | ---------------- | ----------- |
| `004`      | 국민은행    | `170000000001`   | 1,000,000원 |
| `004`      | 국민은행    | `170000000002`   | 0원         |
| `088`      | 신한은행    | `110245000088`   | 1,000,000원 |
| `020`      | 우리은행    | `1002245000020`  | 1,000,000원 |
| `081`      | 하나은행    | `24591000000081` | 1,000,000원 |
| `011`      | 농협은행    | `3010245000011`  | 1,000,000원 |
| `003`      | 기업은행    | `00324500000003` | 1,000,000원 |
| `090`      | 카카오뱅크  | `3333245000090`  | 1,000,000원 |
| `092`      | 토스뱅크    | `100024500092`   | 1,000,000원 |
| `089`      | 케이뱅크    | `100245000089`   | 1,000,000원 |
| `032`      | 부산은행    | `1012450000032`  | 1,000,000원 |
| `031`      | DGB대구은행 | `508245000031`   | 1,000,000원 |
| `131`      | iM뱅크      | `508245000131`   | 1,000,000원 |
| `034`      | 광주은행    | `110245000034`   | 1,000,000원 |
| `023`      | SC제일은행  | `02324500001`    | 1,000,000원 |
| `027`      | 씨티은행    | `0272450001`     | 1,000,000원 |
| `002`      | KDB산업은행 | `00224500000002` | 1,000,000원 |
| `007`      | 수협은행    | `101245000007`   | 1,000,000원 |
| `045`      | 새마을금고  | `9002245000045`  | 1,000,000원 |
| `048`      | 신협        | `0482450000048`  | 1,000,000원 |
| `071`      | 우체국      | `07124500000071` | 1,000,000원 |

계좌번호는 은행별 일반적인 자릿수 형태를 본뜬 테스트 전용 합성값이며 실제 고객정보와
관련이 없습니다. `131`은 PR #249의 SPEC 4.1.0에서 DGB대구은행과 별도 표시하는 iM뱅크에
배정한 프로젝트 전용 canonical 코드입니다.

같은 명령을 다시 실행하면 전용 테스트 계정과 `[TEST-17]` 근무 건만 위 상태로 되돌립니다. 다른 사용자의 데이터는 삭제하지 않습니다. 전체 DB를 초기화하는 `docker compose down -v`나 Flyway `clean`을 이 Seed의 재실행 방법으로 사용하지 않습니다.

## 선택적 초대 수락 E2E Fixture

WORKER 초대 조회와 수락을 Browser로 확인할 때만 실행합니다. 위 계약·에스크로 Seed는 근무와 초대를 이미 `ACCEPTED`로 만들기 때문에 수락 흐름 자체를 재현할 수 없어 별도 Fixture를 둡니다.

### 선행 조건

| 항목           | 확인 내용                                                    |
| -------------- | ------------------------------------------------------------ |
| 로컬 MySQL     | Compose `db`가 `healthy`                                     |
| Backend Tomcat | `http://localhost:8080`에서 기동 중                          |
| 초대 설정      | 로컬 properties에 `invite.hmac.secret`과 `invite.web-origin` |

계약·에스크로 Seed와 달리 Backend 기동이 필요합니다. 이 Fixture는 DRAFT 근무와 초대를 SQL이 아니라 실제 OWNER API로 만들기 때문입니다. `invite.*` 설정이 없으면 Spring Root Context가 뜨지 않아 초대 발급 단계에서 멈춥니다.

### 실행

```powershell
npm.cmd run db:fixture:invite
```

이 명령은 미적용 Migration을 먼저 적용하고, [`test-invitation-accept.sql`](../../backend/src/test/resources/db/seed/test-invitation-accept.sql)로 계정·사업장·지갑을 준비한 뒤, [`prepare-invitation-fixture.js`](../../scripts/prepare-invitation-fixture.js)가 OWNER로 로그인해 DRAFT 근무와 초대를 만듭니다. 대상 DB가 폐기 가능한 로컬 DB임을 확인하지 못하면 SQL에 닿기 전에 중단합니다.

성공하면 다음 상태와 함께 초대 URL을 출력합니다.

| 항목           | 초기 상태                                            |
| -------------- | ---------------------------------------------------- |
| 사장님 로그인  | `test_owner_267` / `Test1234!`                       |
| 근로자 로그인  | `test_worker_267` / `Test1234!`                      |
| 사업장         | `Gig-Hub 초대 수락 E2E 매장`, 사업자번호 `0000000267` |
| 근무·일급      | 실행일 +7일 09:00~18:00, 무급 휴게 60분·300,000원    |
| 사장님 지갑    | 가용 1,000,000원, 잠금 0원                           |
| 근로자 지갑    | 가용 0원                                             |
| 업무 처리 상태 | 근무 `DRAFT`, 초대 `PENDING`, 계약·에스크로·정산 없음 |

초대 Token 원문은 이 출력의 URL 안에만 있고 저장소에는 Token Hash만 남습니다. 출력된 URL을 파일, 이슈, 채팅, Console 로그에 붙여넣지 않습니다. 필요하면 명령을 다시 실행해 새 URL을 받습니다.

### E2E 확인 절차

1. 로그아웃 상태의 Browser로 출력된 초대 URL을 엽니다. WORKER 로그인으로 이동하고 원래 경로가 보존되는지 확인합니다.
2. `test_worker_267`로 로그인한 뒤 초대 경로로 복귀해 조건이 다시 조회되는지 확인합니다.
3. 근무 제목, 시간, 사업장, 휴게, 일급, 조건 Version이 읽기 전용으로 표시되는지 확인합니다.
4. 수락을 실행하고 근무·초대·계약·에스크로·정산·지갑 원장을 대사합니다. 일급 300,000원이 계약 금액, 에스크로 금액, 정산 예정 금액과 같고 사장님 지갑이 가용 700,000원·잠금 300,000원으로 바뀌어야 합니다.
5. OWNER와 WORKER가 같은 계약 최종본을 보는지 확인합니다.
6. 새로고침, 뒤로가기, 중복 클릭, 응답 유실 후 재시도에서 계약과 HOLD가 한 번만 생성되는지 확인합니다.

### 대사 SQL

4번과 6번은 화면만으로 판정하지 않고 다음 질의로 확인합니다.

```sql
SET @wc = (
    SELECT work_case.id
    FROM work_cases work_case
    JOIN users owner_user
        ON owner_user.id = work_case.employer_id
       AND owner_user.login_id = 'test_owner_267'
);

-- 금액은 네 곳이 모두 같아야 합니다.
SELECT
    (SELECT agreed_wage FROM work_cases WHERE id = @wc) AS work_case_wage,
    (SELECT agreed_wage FROM work_contracts WHERE work_case_id = @wc) AS contract_wage,
    (SELECT amount FROM escrows WHERE work_case_id = @wc) AS escrow_amount,
    (SELECT amount FROM settlements WHERE work_case_id = @wc) AS settlement_amount;

-- 수락 뒤 상태입니다.
SELECT
    (SELECT status FROM work_cases WHERE id = @wc) AS work_case,
    (SELECT status FROM work_invitations WHERE work_case_id = @wc) AS invitation,
    (SELECT status FROM escrows WHERE work_case_id = @wc) AS escrow,
    (SELECT status FROM settlements WHERE work_case_id = @wc) AS settlement;

-- 중복 수락이 없으면 네 값이 모두 1입니다.
SELECT
    (SELECT COUNT(*) FROM work_contracts WHERE work_case_id = @wc) AS contracts,
    (SELECT COUNT(*) FROM escrows WHERE work_case_id = @wc) AS escrows,
    (SELECT COUNT(*) FROM settlements WHERE work_case_id = @wc) AS settlements,
    (SELECT COUNT(*) FROM wallet_transactions
      WHERE work_case_id = @wc AND transaction_type = 'ESCROW_HOLD') AS holds;

-- 지갑 원장은 before/after가 이어지고 합계가 변하지 않아야 합니다.
SELECT transaction_type, amount,
       available_before, available_after, locked_before, locked_after
FROM wallet_transactions wallet_transaction
JOIN wallets wallet ON wallet.id = wallet_transaction.wallet_id
JOIN users owner_user ON owner_user.id = wallet.user_id
WHERE owner_user.login_id = 'test_owner_267'
ORDER BY wallet_transaction.id;
```

기대값은 금액 네 곳 모두 `300000`, 상태 `ACCEPTED`/`ACCEPTED`/`HELD`/`WAITING`, 중복 개수 모두 `1`, 원장은 `FUNDING` 1,000,000원 뒤 `ESCROW_HOLD` 300,000원이 이어지고 가용 700,000원·잠금 300,000원으로 합계 1,000,000원이 유지되는 상태입니다.

계약 최종본은 OWNER와 WORKER가 각각 `GET /api/documents/{documentId}/file`로 받은 파일이 같은지 비교해 확인합니다.

### 재실행 범위

같은 명령을 다시 실행하면 `test_owner_267` 사업장에 속한 근무와 그 하위 초대·계약·에스크로·정산·지갑 원장·멱등 Claim만 지우고 위 초기 상태로 되돌립니다. 계약·에스크로 Seed의 `[TEST-17]` 데이터, 다른 사용자의 데이터, 공용 Mock 계좌는 대상이 아닙니다.

수락까지 진행한 수동 테스트 이력을 보존해야 한다면 재실행하지 말고 별도 로컬 DB나 Docker volume에서 Fixture를 실행합니다. 전체 DB를 초기화하는 `docker compose down -v`나 Flyway `clean`을 재실행 방법으로 사용하지 않습니다.

## 중지와 데이터 보존

```powershell
docker compose down
```

컨테이너와 네트워크는 제거되지만 `mysql-data` Named Volume의 데이터는 유지됩니다.

> [!CAUTION]
> `docker compose down -v`는 Named Volume과 로컬 DB 전체를 삭제합니다. 일반 시작·중지·오류 복구 절차로 사용하지 않습니다. 데이터 초기화가 별도 작업의 명시적 목적이고 삭제 대상을 확인한 경우에만 수행합니다.

## 스키마 변경 절차

Flyway Migration과 모든 DDL SQL은 PM·Repository Administrator가 관리합니다. 일반 구현
에이전트는 필요한 테이블·컬럼·제약·데이터 전환을 분석해 소유자에게 보고하고 직접 작성하거나
재생성하지 않습니다. 예외는 현재 개인 에이전트에게 대상 Migration 또는 DDL 릴리스와 범위를
명시한 관리자 요청이 있을 때뿐입니다. 이 예외도 공유·Staging·Production DB 실행 권한으로
확대되지 않으며, 적용된 기존 Migration을 수정하는 권한을 포함하지 않습니다.

승인된 스키마 릴리스는 다음 절차를 따릅니다.

1. 현재 Migration Head보다 큰 새 Version의 `V<version>__<description>.sql`을 추가합니다.
2. 이미 공유되었거나 적용된 Versioned Migration은 수정하거나 삭제하지 않습니다.
3. `prepareFlywayDriver`, `info`, `db:migrate`, `validate`, `info` 순서로 확인합니다.
4. 변경된 Mapper XML과 Service 트랜잭션의 관련 테스트를 실행합니다.
5. 같은 PR에서 [`../agent/SCHEMA_OVERVIEW.md`](../agent/SCHEMA_OVERVIEW.md)의 Head, 테이블 관계와 불변식을 갱신합니다.
6. 파괴적 변경, 수동 데이터 보정 또는 팀원이 수행할 작업이 있으면 PR과 사람용 안내에 명시합니다.

소유자가 작성한 Migration을 검증하는 에이전트는 격리된 일회용 DB만 사용할 수 있습니다.
공유·팀·사용자 DB에는 에이전트가 임의로 스키마 변경을 적용하지 않습니다.

Checksum 불일치가 발생해도 `repair`를 먼저 실행하지 않습니다. 적용된 SQL이 변경되었는지
확인하고 소유자에게 보고합니다. 원본 복구나 후속 Migration 작성은 소유자 또는 해당 범위를
명시적으로 받은 관리자 작업자가 수행합니다.

## 문제 해결

| 증상                                   | 확인과 조치                                                                                             |
| -------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| Docker API 또는 pipe 연결 오류         | Docker Desktop을 실행하고 Linux 엔진 준비가 끝났는지 확인합니다.                                        |
| `db`가 `unhealthy`                     | `docker compose logs --tail 100 db`로 초기화·인증 오류를 확인합니다.                                    |
| Flyway Driver 파일 또는 mount 오류     | `.\backend\gradlew.bat -p backend prepareFlywayDriver`를 다시 실행합니다.                               |
| 호스트 포트 충돌                       | `.env`의 `MYSQL_PORT`와 JDBC URL 포트를 함께 바꿉니다.                                                  |
| `.env` 비밀번호 변경 후 접근 거부      | MySQL 초기 계정값은 빈 Volume을 처음 만들 때만 적용됩니다. 기존 Volume의 자격 증명과 혼동하지 않습니다. |
| `users` 테이블이 없다는 DB 테스트 실패 | 먼저 `npm.cmd run db:migrate`를 실행합니다.                                                             |
| Flyway Checksum 오류                   | 적용된 Migration의 수정 여부를 확인하고 원본을 복구합니다. 즉시 `repair`하지 않습니다.                  |
| 애플리케이션 설정 파일 오류            | `-Dgighub.database.config` 절대경로와 필수 JDBC 속성을 확인합니다.                                      |

## 완료 기준

- `docker compose config --quiet`가 성공합니다.
- `db` 서비스가 `healthy`입니다.
- `db:migrate`를 두 번 실행해도 안전하며 두 번째 실행은 최신 상태를 보고합니다.
- `flyway validate`가 모든 Migration을 검증합니다.
- `flyway info`의 Head가 이 문서 및 Schema Overview와 일치합니다.
- 스키마 전용 DB 테스트가 새 Head의 제약을 통과하고, 호환 애플리케이션을 함께 검증할 때는 전체 `databaseTest`도 통과합니다.
- 비밀정보, 실제 개인정보와 실제 계좌정보가 Git, Seed와 로그에 포함되지 않습니다.
