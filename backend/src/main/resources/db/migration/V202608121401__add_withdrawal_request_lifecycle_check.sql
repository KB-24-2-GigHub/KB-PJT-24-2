SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- READY와 COMPLETED만 현재 출금 writer가 확정합니다. PROCESSING·FAILED·대사 상태는 후속
-- 복구 정책이 정해지기 전까지 기존 허용 범위를 유지합니다.
CREATE TEMPORARY TABLE migration_202608121401_preflight (
    category VARCHAR(80) NOT NULL,
    invalid_count BIGINT UNSIGNED NOT NULL,
    CONSTRAINT ck_migration_202608121401_preflight CHECK (invalid_count = 0)
);

INSERT INTO migration_202608121401_preflight (category, invalid_count)
SELECT 'withdrawal_request_lifecycle', COUNT(*)
FROM withdrawal_requests
WHERE NOT (
    (status = 'READY'
        AND mock_bank_transaction_id IS NULL
        AND failure_code IS NULL
        AND completed_at IS NULL)
    OR
    (status = 'COMPLETED'
        AND mock_bank_transaction_id IS NOT NULL
        AND failure_code IS NULL
        AND completed_at IS NOT NULL)
    OR status IN ('PROCESSING', 'FAILED', 'RECONCILIATION_REQUIRED')
);

INSERT INTO migration_202608121401_preflight (category, invalid_count)
SELECT 'withdrawal_request_constraint_shape', IF(
    NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'withdrawal_requests'
          AND CAST(constraint_name AS BINARY) = CAST('ck_withdrawal_requests_lifecycle' AS BINARY)
    ) OR EXISTS (
        SELECT 1
        FROM information_schema.table_constraints tc
        JOIN information_schema.check_constraints cc
          ON cc.constraint_schema = tc.constraint_schema
         AND CAST(cc.constraint_name AS BINARY) = CAST(tc.constraint_name AS BINARY)
        WHERE tc.constraint_schema = DATABASE()
          AND tc.table_name = 'withdrawal_requests'
          AND CAST(tc.constraint_name AS BINARY) = CAST('ck_withdrawal_requests_lifecycle' AS BINARY)
          AND tc.constraint_type = 'CHECK'
          AND tc.enforced = 'YES'
          AND SHA2(cc.check_clause, 256) =
              'fb424cc4144c60c9360ecca5855bd56218bb8e570303909de5365ef39bc0a772'
    ), 0, 1
);

DROP TEMPORARY TABLE migration_202608121401_preflight;

SET @ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'withdrawal_requests'
          AND CAST(constraint_name AS BINARY) = CAST('ck_withdrawal_requests_lifecycle' AS BINARY)
    ),
    'DO 0',
    'ALTER TABLE withdrawal_requests ADD CONSTRAINT ck_withdrawal_requests_lifecycle CHECK ((status = ''READY'' AND mock_bank_transaction_id IS NULL AND failure_code IS NULL AND completed_at IS NULL) OR (status = ''COMPLETED'' AND mock_bank_transaction_id IS NOT NULL AND failure_code IS NULL AND completed_at IS NOT NULL) OR status IN (''PROCESSING'', ''FAILED'', ''RECONCILIATION_REQUIRED''))'
);
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
