SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- 기존 행의 의미를 추정해서 고치지 않는다. 아래 점검 중 하나라도 0이 아니면 CHECK 추가 전에
-- Migration을 중단하고, 운영자가 상태·원장·분쟁 내용을 대사한 뒤 별도 조치를 결정해야 한다.
CREATE TEMPORARY TABLE migration_202608112307_preflight (
    category VARCHAR(80) NOT NULL,
    invalid_count BIGINT UNSIGNED NOT NULL,
    CONSTRAINT ck_migration_202608112307_preflight CHECK (invalid_count = 0)
);

INSERT INTO migration_202608112307_preflight (category, invalid_count)
SELECT 'settlement_state_shape', COUNT(*)
FROM settlements
WHERE NOT (
        (status = 'WAITING'
            AND approved_by_user_id IS NULL
            AND due_at IS NULL
            AND processing_at IS NULL
            AND completed_at IS NULL
            AND failure_code IS NULL)
        OR
        (status IN ('SCHEDULED', 'ON_HOLD')
            AND approved_by_user_id IS NULL
            AND due_at IS NOT NULL
            AND processing_at IS NULL
            AND completed_at IS NULL
            AND failure_code IS NULL)
        OR
        (status = 'PROCESSING'
            AND processing_at IS NOT NULL
            AND completed_at IS NULL
            AND failure_code IS NULL
            AND (due_at IS NOT NULL OR approved_by_user_id IS NOT NULL))
        OR
        (status = 'COMPLETED'
            AND due_at IS NOT NULL
            AND processing_at IS NOT NULL
            AND completed_at IS NOT NULL
            AND failure_code IS NULL)
        OR
        (status = 'FAILED'
            AND approved_by_user_id IS NULL
            AND due_at IS NOT NULL
            AND processing_at IS NULL
            AND completed_at IS NULL
            AND failure_code IS NOT NULL)
    );

-- PROCESSING은 자금 Transaction과 따로 Commit될 수 없다. 기존 행은 중단 시점과 원장 결과를
-- 알 수 없는 고착 상태이므로 자동 완료·Rollback하지 않고 모두 수동 대사 대상으로 격리한다.
INSERT INTO migration_202608112307_preflight (category, invalid_count)
SELECT 'stuck_processing_settlements', COUNT(*)
FROM settlements
WHERE status = 'PROCESSING';

-- 기존 FAILED에는 신규 retry_count를 정확히 복원할 근거가 없다. 0이나 5로 추정하지 않는다.
INSERT INTO migration_202608112307_preflight (category, invalid_count)
SELECT 'legacy_failed_settlements_without_retry_count', COUNT(*)
FROM settlements
WHERE status = 'FAILED';

-- COMPLETED는 컬럼 모양만으로 완료를 인정하지 않는다. RELEASED Escrow와 금액이 일치하고,
-- OWNER 잠금 감소·WORKER 가용 증가를 각각 증명하는 ESCROW_RELEASE 원장이 정확히 한 쌍이어야
-- 한다. #72 이전 ERLO/ERLI Key와 현재 Settlement ID 기반 Key는 모두 실제 저장 이력이므로
-- Key 문자열을 추정하지 않고 자금 Snapshot·소유자·Escrow 참조로 같은 불변식을 검증한다.
INSERT INTO migration_202608112307_preflight (category, invalid_count)
SELECT 'completed_settlement_fund_integrity', COUNT(*)
FROM settlements s
JOIN work_cases wc ON wc.id = s.work_case_id
LEFT JOIN escrows e ON e.work_case_id = s.work_case_id
WHERE s.status = 'COMPLETED'
  AND (
      wc.employer_id = wc.worker_id
      OR wc.agreed_wage <> s.amount
      OR e.id IS NULL
      OR e.status <> 'RELEASED'
      OR e.amount <> s.amount
      OR (
          SELECT COUNT(*)
          FROM wallet_transactions wt
          WHERE wt.work_case_id = s.work_case_id
            AND wt.transaction_type = 'ESCROW_RELEASE'
            AND wt.amount = s.amount
            AND wt.reference_type = 'ESCROW'
            AND wt.reference_id = e.id
      ) <> 2
      OR (
          SELECT COUNT(*)
          FROM wallet_transactions wt
          JOIN wallets w ON w.id = wt.wallet_id
          WHERE wt.work_case_id = s.work_case_id
            AND w.user_id = wc.employer_id
            AND wt.transaction_type = 'ESCROW_RELEASE'
            AND wt.amount = s.amount
            AND wt.reference_type = 'ESCROW'
            AND wt.reference_id = e.id
            AND wt.available_before = wt.available_after
            AND wt.locked_before >= wt.amount
            AND wt.locked_after = wt.locked_before - wt.amount
      ) <> 1
      OR (
          SELECT COUNT(*)
          FROM wallet_transactions wt
          JOIN wallets w ON w.id = wt.wallet_id
          WHERE wt.work_case_id = s.work_case_id
            AND w.user_id = wc.worker_id
            AND wt.transaction_type = 'ESCROW_RELEASE'
            AND wt.amount = s.amount
            AND wt.reference_type = 'ESCROW'
            AND wt.reference_id = e.id
            AND wt.available_after >= wt.available_before
            AND wt.available_after - wt.available_before = wt.amount
            AND wt.locked_before = wt.locked_after
      ) <> 1
  );

-- 기존 disputes 행에는 승인된 제목 원문이 없으므로 빈 문자열이나 content 일부로 채우지 않는다.
-- 행이 있으면 제목을 확인·입력하는 별도 관리자 절차 없이는 이 Migration을 적용할 수 없다.
INSERT INTO migration_202608112307_preflight (category, invalid_count)
SELECT 'disputes_without_approved_title', COUNT(*)
FROM disputes;

-- 같은 Table 안에서 DDL이 일부만 적용된 상태는 자동 보정하지 않는다. 완전 미적용 또는 이
-- Migration이 만든 완전 적용 상태만 허용해야 재실행이 기존 제약을 느슨하게 만들지 않는다.
INSERT INTO migration_202608112307_preflight (category, invalid_count)
SELECT 'settlements_schema_shape', IF(
    (
        (SELECT COUNT(*) FROM information_schema.columns
         WHERE table_schema = DATABASE() AND table_name = 'settlements'
           AND column_name IN ('retry_count', 'last_failure_at', 'next_retry_at')) = 0
        AND EXISTS (
            SELECT 1
            FROM information_schema.table_constraints tc
            JOIN information_schema.check_constraints cc
              ON cc.constraint_schema = tc.constraint_schema
             AND cc.constraint_name = tc.constraint_name
            WHERE tc.constraint_schema = DATABASE() AND tc.table_name = 'settlements'
              AND tc.constraint_name = 'ck_settlements_status'
              AND tc.constraint_type = 'CHECK'
              AND SHA2(cc.check_clause, 256) =
                  'e74e8f56affcae6e1641eec9311abdcddccfd8742386191f5dd08746bc681033'
        )
        AND NOT EXISTS (
            SELECT 1 FROM information_schema.table_constraints
            WHERE constraint_schema = DATABASE() AND table_name = 'settlements'
              AND constraint_name = 'ck_settlements_lifecycle'
        )
    ) OR (
        EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'settlements'
              AND column_name = 'retry_count' AND column_type = 'tinyint unsigned'
              AND is_nullable = 'NO' AND column_default = '0'
        )
        AND EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'settlements'
              AND column_name = 'last_failure_at' AND data_type = 'datetime'
              AND datetime_precision = 6 AND is_nullable = 'YES'
              AND column_default IS NULL
        )
        AND EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'settlements'
              AND column_name = 'next_retry_at' AND data_type = 'datetime'
              AND datetime_precision = 6 AND is_nullable = 'YES'
              AND column_default IS NULL
        )
        AND NOT EXISTS (
            SELECT 1 FROM information_schema.table_constraints
            WHERE constraint_schema = DATABASE() AND table_name = 'settlements'
              AND constraint_name = 'ck_settlements_status'
        )
        AND EXISTS (
            SELECT 1
            FROM information_schema.table_constraints tc
            JOIN information_schema.check_constraints cc
              ON cc.constraint_schema = tc.constraint_schema
             AND cc.constraint_name = tc.constraint_name
            WHERE tc.constraint_schema = DATABASE() AND tc.table_name = 'settlements'
              AND tc.constraint_name = 'ck_settlements_lifecycle'
              AND tc.constraint_type = 'CHECK'
              AND SHA2(cc.check_clause, 256) =
                  '63ab283bba29001e37918829d05e48c9b0c749ebc28847c909d0ffdd755b5a79'
        )
    ), 0, 1
);

INSERT INTO migration_202608112307_preflight (category, invalid_count)
SELECT 'disputes_schema_shape', IF(
    (
        NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'disputes'
              AND column_name = 'title'
        )
        AND NOT EXISTS (
            SELECT 1 FROM information_schema.table_constraints
            WHERE constraint_schema = DATABASE() AND table_name = 'disputes'
              AND constraint_name = 'ck_disputes_title'
        )
    ) OR (
        EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'disputes'
              AND column_name = 'title' AND column_type = 'varchar(100)'
              AND character_maximum_length = 100 AND is_nullable = 'NO'
              AND column_default IS NULL
        )
        AND EXISTS (
            SELECT 1
            FROM information_schema.table_constraints tc
            JOIN information_schema.check_constraints cc
              ON cc.constraint_schema = tc.constraint_schema
             AND cc.constraint_name = tc.constraint_name
            WHERE tc.constraint_schema = DATABASE() AND tc.table_name = 'disputes'
              AND tc.constraint_name = 'ck_disputes_title'
              AND tc.constraint_type = 'CHECK'
              AND SHA2(cc.check_clause, 256) =
                  '05bcbf202c05c8688d853a171d4a473c4d49348800f6b358a7fea36cd3368d16'
        )
    ), 0, 1
);

DROP TEMPORARY TABLE migration_202608112307_preflight;

-- MySQL은 한 ALTER TABLE 문 안에서는 원자적으로 적용한다. Settlement의 컬럼·기존 CHECK 제거·
-- 새 CHECK 추가를 한 문장으로 묶어 상태 allowlist가 사라진 중간 상태를 남기지 않는다.
SET @ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE() AND table_name = 'settlements'
          AND constraint_name = 'ck_settlements_lifecycle'
    ),
    'DO 0',
    'ALTER TABLE settlements ADD COLUMN retry_count TINYINT UNSIGNED NOT NULL DEFAULT 0 AFTER failure_code, ADD COLUMN last_failure_at DATETIME(6) NULL AFTER retry_count, ADD COLUMN next_retry_at DATETIME(6) NULL AFTER last_failure_at, DROP CHECK ck_settlements_status, ADD CONSTRAINT ck_settlements_lifecycle CHECK ((status = ''WAITING'' AND approved_by_user_id IS NULL AND due_at IS NULL AND processing_at IS NULL AND completed_at IS NULL AND failure_code IS NULL AND retry_count = 0 AND last_failure_at IS NULL AND next_retry_at IS NULL) OR (status IN (''SCHEDULED'', ''ON_HOLD'') AND approved_by_user_id IS NULL AND due_at IS NOT NULL AND processing_at IS NULL AND completed_at IS NULL AND ((retry_count = 0 AND failure_code IS NULL AND last_failure_at IS NULL AND next_retry_at IS NULL) OR (retry_count BETWEEN 1 AND 4 AND failure_code IS NOT NULL AND last_failure_at IS NOT NULL AND next_retry_at IS NOT NULL))) OR (status = ''PROCESSING'' AND processing_at IS NOT NULL AND completed_at IS NULL AND failure_code IS NULL AND next_retry_at IS NULL AND (due_at IS NOT NULL OR approved_by_user_id IS NOT NULL) AND ((retry_count = 0 AND last_failure_at IS NULL) OR (retry_count BETWEEN 1 AND 4 AND last_failure_at IS NOT NULL))) OR (status = ''COMPLETED'' AND due_at IS NOT NULL AND processing_at IS NOT NULL AND completed_at IS NOT NULL AND failure_code IS NULL AND next_retry_at IS NULL AND ((retry_count = 0 AND last_failure_at IS NULL) OR (retry_count BETWEEN 1 AND 4 AND last_failure_at IS NOT NULL))) OR (status = ''REFUNDED'' AND approved_by_user_id IS NOT NULL AND due_at IS NULL AND processing_at IS NOT NULL AND completed_at IS NOT NULL AND failure_code IS NULL AND retry_count = 0 AND last_failure_at IS NULL AND next_retry_at IS NULL) OR (status = ''FAILED'' AND approved_by_user_id IS NULL AND due_at IS NOT NULL AND processing_at IS NULL AND completed_at IS NULL AND failure_code IS NOT NULL AND retry_count BETWEEN 1 AND 5 AND last_failure_at IS NOT NULL AND next_retry_at IS NULL))'
);
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

-- 두 번째 Table도 컬럼과 CHECK를 한 문장으로 묶는다. 두 Table 사이 실패는 가능하지만 위의
-- 완전 적용 감지 덕분에 Flyway repair 후 재실행할 때 settlements를 다시 변경하지 않는다.
SET @ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE() AND table_name = 'disputes'
          AND constraint_name = 'ck_disputes_title'
    ),
    'DO 0',
    'ALTER TABLE disputes ADD COLUMN title VARCHAR(100) NOT NULL AFTER dispute_type, ADD CONSTRAINT ck_disputes_title CHECK (title = TRIM(title) AND CHAR_LENGTH(title) BETWEEN 1 AND 100)'
);
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
