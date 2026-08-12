SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- 현재 schema·승인 계약과 writer가 증명하는 UNFUNDED·HELD·RELEASED·REFUNDED만
-- 시간 모양을 잠급니다. REFUNDED writer 구현 자체는 #174의 소유입니다.
-- Escrow ON_HOLD는 제품 계약이 확정되지 않았으므로 기존 행을 임의 해석하지 않습니다.
CREATE TEMPORARY TABLE migration_202608121402_preflight (
    category VARCHAR(80) NOT NULL,
    invalid_count BIGINT UNSIGNED NOT NULL,
    CONSTRAINT ck_migration_202608121402_preflight CHECK (invalid_count = 0)
);

INSERT INTO migration_202608121402_preflight (category, invalid_count)
SELECT 'escrow_lifecycle', COUNT(*)
FROM escrows
WHERE NOT (
    (status = 'UNFUNDED'
        AND held_at IS NULL
        AND released_at IS NULL
        AND refunded_at IS NULL
        AND on_hold_at IS NULL)
    OR
    (status = 'HELD'
        AND held_at IS NOT NULL
        AND released_at IS NULL
        AND refunded_at IS NULL
        AND on_hold_at IS NULL)
    OR
    (status = 'RELEASED'
        AND held_at IS NOT NULL
        AND released_at IS NOT NULL
        AND released_at >= held_at
        AND refunded_at IS NULL
        AND on_hold_at IS NULL)
    OR
    (status = 'REFUNDED'
        AND held_at IS NOT NULL
        AND refunded_at IS NOT NULL
        AND refunded_at >= held_at
        AND released_at IS NULL
        AND on_hold_at IS NULL)
    OR status = 'ON_HOLD'
);

INSERT INTO migration_202608121402_preflight (category, invalid_count)
SELECT 'escrow_constraint_shape', IF(
    NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'escrows'
          AND CAST(constraint_name AS BINARY) = CAST('ck_escrows_lifecycle' AS BINARY)
    ) OR EXISTS (
        SELECT 1
        FROM information_schema.table_constraints tc
        JOIN information_schema.check_constraints cc
          ON cc.constraint_schema = tc.constraint_schema
         AND CAST(cc.constraint_name AS BINARY) = CAST(tc.constraint_name AS BINARY)
        WHERE tc.constraint_schema = DATABASE()
          AND tc.table_name = 'escrows'
          AND CAST(tc.constraint_name AS BINARY) = CAST('ck_escrows_lifecycle' AS BINARY)
          AND tc.constraint_type = 'CHECK'
          AND tc.enforced = 'YES'
          AND SHA2(cc.check_clause, 256) =
              '356cc7defc1ed3a8b900dda540376b8b3ed840fa52896ec62e630211ad91f4c5'
    ), 0, 1
);

DROP TEMPORARY TABLE migration_202608121402_preflight;

SET @ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'escrows'
          AND CAST(constraint_name AS BINARY) = CAST('ck_escrows_lifecycle' AS BINARY)
    ),
    'DO 0',
    'ALTER TABLE escrows ADD CONSTRAINT ck_escrows_lifecycle CHECK ((status = ''UNFUNDED'' AND held_at IS NULL AND released_at IS NULL AND refunded_at IS NULL AND on_hold_at IS NULL) OR (status = ''HELD'' AND held_at IS NOT NULL AND released_at IS NULL AND refunded_at IS NULL AND on_hold_at IS NULL) OR (status = ''RELEASED'' AND held_at IS NOT NULL AND released_at IS NOT NULL AND released_at >= held_at AND refunded_at IS NULL AND on_hold_at IS NULL) OR (status = ''REFUNDED'' AND held_at IS NOT NULL AND refunded_at IS NOT NULL AND refunded_at >= held_at AND released_at IS NULL AND on_hold_at IS NULL) OR status = ''ON_HOLD'')'
);
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
