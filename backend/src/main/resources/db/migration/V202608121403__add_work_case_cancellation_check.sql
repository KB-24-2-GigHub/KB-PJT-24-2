SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- 취소 시각은 CANCELED 상태의 감사 증거입니다. 다른 상태에 취소 시각을 남기거나 취소
-- 상태에서 시각을 비우면 현재 Work lifecycle과 모순되므로 적용 전에 모두 차단합니다.
CREATE TEMPORARY TABLE migration_202608121403_preflight (
    category VARCHAR(80) NOT NULL,
    invalid_count BIGINT UNSIGNED NOT NULL,
    CONSTRAINT ck_migration_202608121403_preflight CHECK (invalid_count = 0)
);

INSERT INTO migration_202608121403_preflight (category, invalid_count)
SELECT 'work_case_cancellation_lifecycle', COUNT(*)
FROM work_cases
WHERE NOT (
    (status = 'CANCELED' AND canceled_at IS NOT NULL)
    OR (status <> 'CANCELED' AND canceled_at IS NULL)
);

INSERT INTO migration_202608121403_preflight (category, invalid_count)
SELECT 'work_case_cancellation_constraint_shape', IF(
    NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'work_cases'
          AND CAST(constraint_name AS BINARY) = CAST('ck_work_cases_cancellation_lifecycle' AS BINARY)
    ) OR EXISTS (
        SELECT 1
        FROM information_schema.table_constraints tc
        JOIN information_schema.check_constraints cc
          ON cc.constraint_schema = tc.constraint_schema
         AND CAST(cc.constraint_name AS BINARY) = CAST(tc.constraint_name AS BINARY)
        WHERE tc.constraint_schema = DATABASE()
          AND tc.table_name = 'work_cases'
          AND CAST(tc.constraint_name AS BINARY) = CAST('ck_work_cases_cancellation_lifecycle' AS BINARY)
          AND tc.constraint_type = 'CHECK'
          AND tc.enforced = 'YES'
          AND SHA2(cc.check_clause, 256) =
              'd9dfbfe3182512d6e496971ad17ef35e624e8f2979332de76beff41b0a9cf970'
    ), 0, 1
);

DROP TEMPORARY TABLE migration_202608121403_preflight;

SET @ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'work_cases'
          AND CAST(constraint_name AS BINARY) = CAST('ck_work_cases_cancellation_lifecycle' AS BINARY)
    ),
    'DO 0',
    'ALTER TABLE work_cases ADD CONSTRAINT ck_work_cases_cancellation_lifecycle CHECK ((status = ''CANCELED'' AND canceled_at IS NOT NULL) OR (status <> ''CANCELED'' AND canceled_at IS NULL))'
);
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
