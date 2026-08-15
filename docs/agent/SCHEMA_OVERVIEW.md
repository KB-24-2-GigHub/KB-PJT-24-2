# Database Schema Overview

## Purpose and authority

This is the compact database context for repository agents. Read it before changing MyBatis mappers, persisted domain behavior, or transaction boundaries. It is not a column catalog and does not replace the DDL. Flyway migrations and all schema DDL are controlled by the human Project Manager or Repository Administrator. Ordinary implementation agents have read-only access; the scoped administrative-release exception is defined only in `PROJECT_RULES.md`.

| Item                   | Current baseline                                                                                               |
| ---------------------- | -------------------------------------------------------------------------------------------------------------- |
| Status                 | Current                                                                                                        |
| Last verified          | 2026-08-12                                                                                                     |
| Schema and DDL editor  | PM or Repository Administrator controlled; ordinary implementation agents have read-only access                |
| Schema source of truth | Owner-authored or owner-adopted tracked `backend/src/main/resources/db/migration/V*.sql`                       |
| Migration head         | `202608121403`                                                                                                 |
| Versioned migrations   | 19                                                                                                             |
| Domain tables          | 24, excluding Flyway's `flyway_schema_history`                                                                 |
| Runtime                | MySQL 8.4.10, InnoDB                                                                                           |
| Readable DDL snapshot  | [`schema-snapshot-202608121403.sql`](../database/schema-snapshot-202608121403.sql), owner-maintained reference |

When this summary and executable configuration disagree, inspect the owner-authored or
owner-adopted migrations, Git tracking, `compose.yaml`, `DatabaseConfig.java`, and
`backend/build.gradle`. Versions `202607311427` through `202608121403` are approved parts of the
current schema. Version `202608041614` adds the independent idempotency Claim store, and version
`202608051337` replaces Mock bank-account user ownership with a four-digit Demo PIN while preserving
account IDs and finance references. Version `202608061428` adds document-Version and structured
denial-reason detail to document access audit rows without rewriting historical rows. Version
`202608111743` closes the audit `action` and `denial_reason` value sets, and `202608111744` separately
closes the `user_badges.badge_type` value set. Versions `202608121400` through `202608121403` add the
currently proven funding, withdrawal, escrow, and work-cancellation lifecycle shapes. Each of those
versions performs a count-only preflight, makes no guessed backfill, and owns one table-level
`ALTER TABLE`. Recovery accepts an already present constraint only when its name, type, enforced state,
and normalized MySQL CHECK-clause SHA-256 exactly match the migration. Update this document when those authoritative
sources prove the summary is stale.
If the executable schema itself needs correction, report the required change to the owner and do
not edit or regenerate SQL.

## Runtime semantics

- Every domain table uses InnoDB, `utf8mb4`, and `utf8mb4_0900_ai_ci`.
- Domain primary keys are single-column `BIGINT UNSIGNED AUTO_INCREMENT` IDs.
- Declared foreign keys use `ON DELETE RESTRICT ON UPDATE RESTRICT`; deletion does not cascade.
- Money is stored as unsigned integer KRW with no fractional unit.
- Most persisted moments use `DATETIME(6)`. The column has no timezone, so the application and JDBC must write and read it as an `Asia/Seoul` wall-clock value.
- HTTP API moment fields use UTC `Instant` values such as `2026-07-31T09:00:00Z`. The server converts between that representation and the `Asia/Seoul` DB storage rule at the boundary.
- Values whose meaning is a calendar date rather than a moment use `DATE` in MySQL and `LocalDate` in Java. Document issue and expiry dates are examples.
- Identity-like tokens, idempotency keys, bank identifiers, and storage keys use exact binary or `ascii_bin` comparison where declared. Hashes and checksums use `BINARY(32)`.

## Migration history

| Version        | File                                                         | Main effect                                                                                           |
| -------------- | ------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------- |
| `202607200001` | `V202607200001__create_gig_hub_baseline.sql`                 | Baseline identity, work, wallet, banking, escrow, attendance, dispute, and document schema            |
| `202607211440` | `V202607211440__add_signup_and_workplace_schema.sql`         | Signup identity fields, user withdrawal lifecycle, workplaces, and work-case ownership linkage        |
| `202607221300` | `V202607221300__support_contract_escrow_test_flow.sql`       | Contract/escrow fixture support and final withdrawal naming/relationship adjustments                  |
| `202607301027` | `V202607301027__remove_invited_from_work_case_status.sql`    | Map legacy `INVITED` work cases to `DRAFT` and remove `INVITED` from the work-case status constraint  |
| `202607301152` | `V202607301152__split_workplace_address.sql`                 | Preserve legacy workplace addresses as road addresses and add an optional nonblank detail address     |
| `202607311427` | `V202607311427__move_qr_tokens_to_workplace_scope.sql`       | Replace work/action QR issuance with one active fixed QR per workplace while preserving legacy rows   |
| `202607311428` | `V202607311428__add_password_reset_tokens.sql`               | Add hashed, expiring, single-active password-reset token lifecycle storage                            |
| `202607311429` | `V202607311429__add_check_out_missing_work_case_status.sql`  | Allow the distinct `CHECK_OUT_MISSING` work-case state and require an assigned worker for that state  |
| `202608041138` | `V202608041138__remove_employer_profiles.sql`                | Drop the unused employer profile table without renaming or copying its legacy contact data            |
| `202608041614` | `V202608041614__add_idempotency_request_claims.sql`          | Add a user-and-operation-scoped Claim store for request fingerprints and successful response replay   |
| `202608051337` | `V202608051337__replace_mock_bank_account_user_with_pin.sql` | Remove Mock account user ownership and add the four-digit ASCII Demo PIN without changing account IDs |
| `202608061428` | `V202608061428__add_document_access_audit_details.sql`       | Link access audits to a version of the same document and store structured denial reasons              |
| `202608111743` | `V202608111743__add_document_access_audit_allowlists.sql` | Restrict audit `action` and audit `denial_reason` to the approved value sets                            |
| `202608111744` | `V202608111744__add_user_badge_type_allowlist.sql`        | Restrict `user_badges.badge_type` to `TRUST_OWNER` and `TRUST_WORKER`                                  |
| `202608112307` | `V202608112307__add_settlement_retry_and_dispute_title.sql` | Add settlement refund/retry lifecycle constraints and the required dispute title                        |
| `202608121400` | `V202608121400__add_funding_order_lifecycle_check.sql`    | Constrain proven `READY` and `COMPLETED` funding-order result shapes                                   |
| `202608121401` | `V202608121401__add_withdrawal_request_lifecycle_check.sql` | Constrain proven `READY` and `COMPLETED` withdrawal result shapes                                    |
| `202608121402` | `V202608121402__add_escrow_lifecycle_check.sql`           | Constrain timestamp shapes for `UNFUNDED`, `HELD`, `RELEASED`, and `REFUNDED` escrow states            |
| `202608121403` | `V202608121403__add_work_case_cancellation_check.sql`     | Require `canceled_at` exactly for `CANCELED` work cases                                                |

Applied or shared versioned migrations are immutable. A newer `V*.sql` file or another DDL artifact may be created only in a scoped administrative release explicitly authorized by the human Project Manager or Repository Administrator.

## Domain inventory

| Domain                                   | Tables                                                                                                                                              |
| ---------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| Identity and organization                | `users`, `password_reset_tokens`, `workplaces`, `user_badges`                                                                                       |
| Work and contract                        | `work_cases`, `work_invitations`, `work_contracts`                                                                                                  |
| Wallet, mock banking, escrow, settlement | `wallets`, `mock_bank_accounts`, `mock_bank_transactions`, `funding_orders`, `withdrawal_requests`, `wallet_transactions`, `escrows`, `settlements` |
| Cross-domain request control             | `idempotency_requests`                                                                                                                              |
| Attendance and dispute                   | `qr_tokens`, `attendance_records`, `disputes`, `dispute_ai_reviews`                                                                                 |
| Documents and signatures                 | `documents`, `document_versions`, `document_signatures`, `document_shares`, `document_access_logs`                                                  |

Inspect the ordered migrations before relying on an exact column, key, index, generated expression, or allowed status value.

## Core relationships and invariants

### Identity and organization

- `users.email` and `users.login_id` are unique. Roles are `OWNER`, `WORKER`, and `ADMIN`.
- User status and `deleted_at` are coupled: `WITHDRAWN` requires a deletion timestamp; other statuses require it to be null.
- M2 has no separate employer profile table. OWNER identity remains in `users`, while business and workplace details belong to `workplaces`.
- `users.phone` and `workplaces.phone` are independent values. The schema keeps their existing `VARCHAR(30)` storage and relies on the application to apply the approved normalization and disclosure policy.
- A user can own multiple `workplaces`. Business registration numbers are unique, `workplaces.road_address` is required, `workplaces.detail_address` is null or nonblank, coordinates must be both null or both non-null and in range, radius is positive, and `DELETED` status must agree with `deleted_at`.
- The composite relationship from `work_cases` to `(workplaces.owner_user_id, workplaces.id)` proves that a selected workplace belongs to the recorded employer.
- `password_reset_tokens` stores only a unique `BINARY(32)` token hash. Generated active-slot uniqueness permits one `ACTIVE` token per user while retaining `USED`, `EXPIRED`, and `REVOKED` history.
- Password-reset status determines audit timestamps: only `USED` requires `used_at`, only `REVOKED` requires `revoked_at`, and neither is populated for `ACTIVE` or `EXPIRED`.
- `user_badges` keeps one row per user and badge type, and `badge_type` accepts only `TRUST_OWNER` and `TRUST_WORKER`. Level, counts, and thresholds live in the `evidence` JSON rather than dedicated columns.

### Work and contract

- A `work_case` has one employer, an optional worker until assignment, one workplace, positive agreed wage, valid start/end times, and a lifecycle status constrained to `DRAFT`, `ACCEPTED`, `READY`, `IN_PROGRESS`, `CHECK_OUT_MISSING`, `COMPLETED`, `NO_SHOW`, or `CANCELED`.
- Invitation delivery and response states belong to `work_invitations`; an unaccepted work case remains `DRAFT`.
- `ACCEPTED`, `READY`, `IN_PROGRESS`, `CHECK_OUT_MISSING`, `COMPLETED`, and `NO_SHOW` work-case states require a worker.
- `CANCELED` requires `canceled_at`, while every other work-case state requires `canceled_at` to be null.
- The database permits `CHECK_OUT_MISSING` and requires its worker, but it does not prove that the work case has a successful check-in and no successful check-out or decide when that state transition occurs.
- Generated active-slot uniqueness permits at most one pending `work_invitation` per work case while preserving terminal invitation history.
- `work_contracts.work_case_id` is unique. A composite foreign key proves that contract parties and agreed wage match the work case.
- The database validates allowed status values and selected state-dependent null rules, not the complete transition graph.

### Wallet, banking, escrow, and settlement

- A user has at most one KRW `wallet`; balances are unsigned.
- `mock_bank_accounts` rows are not owned by users. Their four-digit ASCII `pin` defaults to `0000`, `available_amount` cannot exceed `balance`, and bank/account and fintech identifiers are unique.
- Funding orders, withdrawal requests, and wallet transactions use globally unique idempotency keys in their respective tables.
- A `READY` funding order has no transferred amount, bank transaction, failure code, or completion
  timestamp. A `COMPLETED` funding order requires the transferred amount to equal the expected amount,
  a bank transaction, and a completion timestamp, with no failure code. `FAILED` and
  `RECONCILIATION_REQUIRED` remain outside this state-shape constraint until their recovery meaning is
  fixed.
- A `READY` withdrawal request has no bank transaction, failure code, or completion timestamp. A
  `COMPLETED` withdrawal requires a bank transaction and completion timestamp with no failure code.
  `PROCESSING`, `FAILED`, and `RECONCILIATION_REQUIRED` remain outside this state-shape constraint.
- `idempotency_requests` permits one Claim per `(user_id, operation_code, idempotency_key)`. It stores
  a 32-byte request fingerprint and either an in-progress Claim or a completed 2xx response Snapshot.
  The composite unique key is its only non-primary index and also supports the user foreign key.
- The new Claim store does not replace or backfill the existing finance-table idempotency keys. Runtime
  Claim acquisition, replay, expiry cleanup, immediate conflict responses, and interruption recovery
  remain application responsibilities.
- `wallet_transactions` records before/after snapshots, but the database does not validate ledger arithmetic or the polymorphic reference target.
- `escrows.work_case_id` and `settlements.work_case_id` are each unique. Composite foreign keys require their amounts to equal the work case's agreed wage.
- Escrow timestamps are constrained for the currently proven states: `UNFUNDED` has no lifecycle
  timestamps; `HELD` has only `held_at`; `RELEASED` has `held_at` and a non-earlier `released_at`;
  `REFUNDED` has `held_at` and a non-earlier `refunded_at`. Release and refund evidence are mutually
  exclusive in those terminal states. `ON_HOLD` deliberately remains unconstrained because its timestamp
  meaning is not yet fixed.
- There is no direct foreign key between a settlement and an escrow.
- `settlements` accepts `WAITING`, `SCHEDULED`, `ON_HOLD`, `PROCESSING`, `COMPLETED`, `REFUNDED`,
  and `FAILED` only in approved column shapes. `retry_count`, `last_failure_at`, and
  `next_retry_at` preserve Scheduler failure evidence; `(status, due_at)` remains the candidate index.
- `PROCESSING` must not be committed independently from its money transaction. A pre-existing stuck
  `PROCESSING`, legacy `FAILED`, or ambiguous completed row is rejected by migration preflight rather
  than guessed into the new lifecycle.
- The runtime payout boundary locks `work_cases`, `settlements`, blocking `disputes`, and `escrows` in
  that order, resolves each party's KRW wallet ID once, then locks the two wallets by ascending wallet
  ID. Expected-state balance updates, deterministic settlement-ID ledger entries, Settlement completion,
  and the manual approval Claim completion commit in one transaction.
- `SettlementPayoutExecutor` only joins a caller-owned transaction. Manual approval opens a fresh
  transaction after the external Claim; the future Scheduler must open one short transaction per item,
  use a null approver, and recheck both `due_at` and `next_retry_at` from the locked Settlement row.
- Funding and withdrawal foreign keys preserve the selected Mock account and bank-transaction references, but they do not enforce ACTIVE status, funding PIN approval, or that a withdrawal request user owns its wallet.

### Attendance and dispute

- Current-schema QR rows are workplace-scoped. `(issued_by_user_id, workplace_id)` references the workplace owner, and generated active-slot uniqueness permits one nonce-backed `ACTIVE` QR per workplace.
- `token_nonce` is a public random identifier, not a secret. New static QR rows use `token_nonce` and only `ACTIVE` or `REVOKED`; the application must sign the nonce and workplace ID with an externally configured HMAC key before exposing a QR token.
- Columns prefixed with `legacy_` preserve pre-migration work-case/action/expiry QR history. Legacy rows cannot become `ACTIVE`, and all previously active legacy rows were revoked during migration.
- A revoked QR requires `revoked_at`. Reissue therefore means revoking the current active row and inserting the replacement in one transaction; the schema exists, but the QR API and scan service are not implemented merely by this migration.
- Generated success-slot uniqueness permits one successful check-in and one successful check-out per work case while retaining rejected attempts.
- `attendance_records.early_checkout_confirmed_at` is allowed only on a successful `CHECK_OUT`, preserving an explicit early-checkout confirmation audit moment.
- Separate attendance foreign keys do not prove that the recorded worker is the worker assigned to the work case.
- Generated open-slot uniqueness permits at most one `OPEN` or `UNDER_REVIEW` dispute per work case.
- Every new dispute requires a trimmed title of 1 to 100 characters. Existing disputes without an
  approved original title block the migration and require owner-directed manual reconciliation.
- `dispute_ai_reviews` keeps one active DEMO review per dispute while preserving completed and failed
  execution history. Its foreign key uses `RESTRICT`, so audit rows cannot be erased by deleting a dispute.
- Review request hashes, leases, lifecycle checks, provider response IDs, decisions, reason codes, and
  failure messages are audit evidence. The application performs the external provider call outside a
  database transaction and only applies a result after re-locking and rechecking the active request.

### Documents and signatures

- A work case has at most one document per document type when `work_case_id` is non-null. MySQL nullable-unique behavior permits multiple null work-case values.
- Document version numbers are unique within a document; storage keys are globally unique.
- Composite signature foreign keys prove that the source and signed versions belong to the stated document, and the two versions must differ.
- Generated active-slot uniqueness permits one equivalent active document share while retaining expired or revoked history.
- A composite foreign key proves that a non-null `document_access_logs.document_version_id` belongs
  to the same document. Historical rows and denials before version resolution may keep it null.
- `document_access_logs.action` accepts only `HEALTH_CERT_FILE_VIEW`, `HEALTH_CERT_FILE_DOWNLOAD`,
  `CONTRACT_FILE_VIEW`, `CONTRACT_FILE_DOWNLOAD`, and `DOCUMENT_DETAIL_VIEW`.
- A non-null `document_access_logs.denial_reason` is valid only for `DENIED` and only for
  `PARTY_ACCESS_DENIED`, `DOCUMENT_UNAVAILABLE`, `FILE_UNAVAILABLE`, `CHECKSUM_MISMATCH`, or
  `SIGNED_VERSION_UNAVAILABLE`. Existing rows remain null because their original reason cannot be
  reconstructed safely.

## Approved workflow and enforcement gaps

The table separates current DDL facts, approved product behavior, and stronger database enforcement.
An approved application policy remains authoritative even when the DDL does not encode the whole rule;
that gap alone does not make the product decision unresolved. Outside a scoped administrative release,
agents must route schema changes to the human Project Manager or Repository Administrator and must not
edit Flyway or a DDL snapshot themselves.

| Requirement area                  | Current schema fact                                                                                                            | Product status and schema handoff                                                                                                                                         |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Missing checkout (`ATT-006`)      | `CHECK_OUT_MISSING` is allowed and requires a worker; no attendance fact, transition, timestamp, or scheduler index is encoded | The approved contract uses an end-plus-two-hour Scheduler rule, no late/manual M5 correction, and `WAITING/due_at=null`; application behavior and any later DDL reinforcement remain separate work |
| Fixed workplace radius            | `workplaces.radius_meters` defaults to 100, while it and `work_cases.allowed_radius_meters` accept every positive value        | The approved application policy always writes and checks 100m in current and snapshot data; the owner decides whether DB checks must also require exactly 100             |
| System-generated contracts        | `documents.work_case_id` may be null even for `EMPLOYMENT_CONTRACT`                                                            | The approved service policy permits only system-generated, work-case-linked contracts; the owner decides whether stronger DB enforcement is required                      |
| Three-year contract purge         | `documents.status=DELETED` exists; no dedicated retention, purge-completion, or retry column/index exists                       | SPEC 7.0.0 fixes the Seoul `ends_at` date + 3 years boundary, 02:00 keyset job, DB-first logical deletion, idempotent object purge, and indefinite metadata/audit retention; #131 owns runtime implementation without inferred DDL |
| Trust-badge projection            | One row per user/type; type allowlist and current-row unique key are enforced, while approved evidence remains JSON              | SPEC 7.0.0 fixes cumulative thresholds and lock→recalculate→upsert; #182 owns runtime implementation, with no new columns, history table, or separate backfill             |
| Idempotency request handling      | User, operation, and key Claims are unique; fingerprints, completed 2xx snapshots, and expiry can be stored                    | The application owns Claim acquisition, fingerprint comparison, immediate conflict handling, replay, interruption recovery, and expiry cleanup                           |
| Non-owned Mock account execution  | Account rows have a four-digit PIN and no user FK; existing order, withdrawal, and bank-ledger references remain               | A compatible backend must resolve ACTIVE accounts by bank/account, verify PIN only for new funding, and treat withdrawal accounts as PIN-free destinations                |

`CHECK_OUT_MISSING` is an approved persisted state distinct from `NO_SHOW`. The DDL only permits the
state and requires an assigned worker. The approved product contract places the transition in a server
Scheduler at the end-plus-two-hour boundary for an assigned `IN_PROGRESS` case with a successful
check-in and no successful check-out. It provides no late QR or manual M5 correction and keeps Settlement
at `WAITING/due_at=null`. The DDL does not prove those facts or implement the Scheduler.

SPEC 7.0.0 uses the Seoul date of `work_cases.ends_at` as the contract-retention reference date. At
that date plus three years, the 02:00 job pages by `documentId`, commits `documents.status=DELETED`
before deleting every final and deterministic temporary object, and retries expired DELETED rows.
Document/version metadata, checksums, signatures, shares, access audits, and contract relations remain
indefinitely. The approved workflow deliberately adds no purge-history table, completion marker, or
`updated_at` completion meaning; the schema does not implement the job by itself.

Within the RF program, product-scope reclassification belongs to #282, module ownership belongs to #283,
and #292 adds only the lifecycle shapes proven by current writers and/or the accepted schema and product
contract. The `REFUNDED` escrow shape is approved before its #174 writer exists; #292 does not implement
that writer. Unconstrained recovery and hold states remain application-owned until a later approved
contract and immutable migration define them.

## Application-enforced responsibilities

Database constraints do not replace application authorization or transaction rules. Services must still enforce:

- actor roles and ownership where no composite relationship proves them;
- immutable profile identity fields (`login_id`, `email`, and `name`) and phone-only profile updates;
- normalize `users.phone` and `workplaces.phone` to approved digit-only values and keep phone values out of request, response, and SQL binding logs;
- a fixed 100m workplace radius and the workplace update allowlist that excludes representative, coordinates, and radius; address changes on coordinate-bearing workplaces wait for an approved geocoding/reverification or restriction policy;
- complete state-transition graphs;
- wallet actor alignment, ACTIVE non-owned account resolution by bank/account, funding PIN verification without persistence or logging, PIN-free withdrawal destination checks, and consistent account/ledger locking;
- attendance worker assignment;
- fixed-QR HMAC verification, revoked-token rejection, first/second scan selection, one applicable work case per worker/workplace, location checks, and transactional QR reissue;
- idempotent no-show handling and, after product approval, missing-checkout detection, race handling, resolution, and settlement behavior;
- system-only employment-contract generation, work-case linkage, contract access control, and the approved three-year DB-first logical-deletion and idempotent object-purge policy;
- per-request badge recalculation after locking the user row, followed by current-row upsert with the approved closed evidence fields and no separate backfill;
- complete document-access audit writes: resolved version when available, action, result, and a
  structured reason for every new denial after document resolution;
- password-reset token generation, hashing, delivery, expiry transition, single-use handling, and revocation of the prior active token;
- wallet ledger arithmetic and balance conservation;
- idempotent replay behavior under concurrency;
- lock ordering, rollback behavior, and external mock-bank coordination.

Do not infer an application guarantee merely because related columns each have foreign keys.

## MyBatis and transaction configuration

- The application uses non-Boot Java configuration in `DatabaseConfig`.
- Explicit `@MapperScan` packages are `attendance`, `auth`, `member`, `wallet`, `work`, `contract`,
  `document`, `idempotency`, `invitation`, `badge`, `bank`, `settlement`, and `workplace` under
  `com.gighub.<domain>.mapper`.
- Mapper XML files are loaded from `classpath*:mappers/**/*.xml`.
- `mapUnderscoreToCamelCase` is enabled.
- SQL belongs in MyBatis mapper XML; Java interfaces declare parameters explicitly.
- Transactions use Spring `DataSourceTransactionManager` with `@EnableTransactionManagement`.
- Spring `@PropertySource` reads the external file selected by JVM property `gighub.database.config`; `DatabaseConfig` applies those values to a `HikariDataSource`.
- The application does not run Flyway at startup. Schema evolution belongs to the Flyway container workflow in [`../runbooks/DATABASE_RUNBOOK.md`](../runbooks/DATABASE_RUNBOOK.md).
- The opt-in `databaseTest` runs every JUnit test tagged `database`, including configured connectivity,
  schema constraints, Mapper integration, and Service transaction flows. The default `test` task excludes
  that tag, and the exact selected coverage remains the tagged test sources rather than this summary.

## Required update triggers

Update this file in the same PR when an authorized schema release or editable application configuration establishes any of the following changes:

1. An authorized administrator adds a `V*.sql` migration.
2. A table, column, primary key, foreign key, unique key, check constraint, generated column, or important index changes.
3. A status/type allowlist or state-dependent null rule changes.
4. Money representation, currency, time precision, collation, or timezone changes.
5. `DatabaseConfig` changes mapper packages, mapper XML locations, naming conversion, DataSource, or transaction management.
6. Wallet, escrow, settlement, or withdrawal logic changes ownership, locking, idempotency, or transaction invariants.
7. Compose changes MySQL/Flyway versions, migration mounts, collation, timezone, volume, or clean policy.

For each update:

1. Read all migrations in version order.
2. Record the new migration head and affected domain summary.
3. When verification is in scope, run the existing owner-authored migrations only in a disposable database and verify `flyway migrate`, `validate`, and `info` using the DB Runbook.
4. Run the narrowest mapper, service, transaction, and database tests that cover the change.
5. Keep detailed SQL in administrator-approved migrations rather than duplicating a full schema dump here. Ordinary implementation agents must not regenerate or edit `docs/database/` DDL snapshots; any administrative exception follows `PROJECT_RULES.md`.
