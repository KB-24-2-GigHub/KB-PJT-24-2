SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- READY와 COMPLETED는 현재 충전 writer가 실제로 만드는 두 상태입니다. 실패·대사 상태는
-- 아직 복구 의미가 확정되지 않았으므로 이번 제약에서 모양을 추정하지 않습니다.
CREATE TEMPORARY TABLE migration_202608121400_preflight (
    category VARCHAR(80) NOT NULL,
    invalid_count BIGINT UNSIGNED NOT NULL,
    CONSTRAINT ck_migration_202608121400_preflight CHECK (invalid_count = 0)
);

INSERT INTO migration_202608121400_preflight (category, invalid_count)
SELECT 'funding_order_lifecycle', COUNT(*)
FROM funding_orders
WHERE NOT (
    (status = 'READY'
        AND transferred_amount IS NULL
        AND mock_bank_transaction_id IS NULL
        AND failure_code IS NULL
        AND completed_at IS NULL)
    OR
    (status = 'COMPLETED'
        AND transferred_amount IS NOT NULL
        AND transferred_amount = expected_amount
        AND mock_bank_transaction_id IS NOT NULL
        AND failure_code IS NULL
        AND completed_at IS NOT NULL)
    OR status IN ('FAILED', 'RECONCILIATION_REQUIRED')
);

-- 한 ALTER TABLE만 실행하므로 같은 Table 안에 부분 적용 상태는 남지 않습니다. Flyway
-- history 기록 뒤 장애가 난 경우에는 같은 이름·내용의 완전 적용 상태만 재실행을 허용합니다.
INSERT INTO migration_202608121400_preflight (category, invalid_count)
SELECT 'funding_order_constraint_shape', IF(
    NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'funding_orders'
          AND CAST(constraint_name AS BINARY) = CAST('ck_funding_orders_lifecycle' AS BINARY)
    ) OR EXISTS (
        SELECT 1
        FROM information_schema.table_constraints tc
        JOIN information_schema.check_constraints cc
          ON cc.constraint_schema = tc.constraint_schema
         AND CAST(cc.constraint_name AS BINARY) = CAST(tc.constraint_name AS BINARY)
        WHERE tc.constraint_schema = DATABASE()
          AND tc.table_name = 'funding_orders'
          AND CAST(tc.constraint_name AS BINARY) = CAST('ck_funding_orders_lifecycle' AS BINARY)
          AND tc.constraint_type = 'CHECK'
          AND tc.enforced = 'YES'
          AND SHA2(cc.check_clause, 256) =
              'ac7e3a0379b2081f41a7511fcd5c4ab8268bb42b364038223387501a07b3a5cc'
    ), 0, 1
);

DROP TEMPORARY TABLE migration_202608121400_preflight;

SET @ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'funding_orders'
          AND CAST(constraint_name AS BINARY) = CAST('ck_funding_orders_lifecycle' AS BINARY)
    ),
    'DO 0',
    'ALTER TABLE funding_orders ADD CONSTRAINT ck_funding_orders_lifecycle CHECK ((status = ''READY'' AND transferred_amount IS NULL AND mock_bank_transaction_id IS NULL AND failure_code IS NULL AND completed_at IS NULL) OR (status = ''COMPLETED'' AND transferred_amount IS NOT NULL AND transferred_amount = expected_amount AND mock_bank_transaction_id IS NOT NULL AND failure_code IS NULL AND completed_at IS NOT NULL) OR status IN (''FAILED'', ''RECONCILIATION_REQUIRED''))'
);
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
