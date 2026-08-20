SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- 이 Migration은 약정액과 실제 지급·환불 결과를 분리합니다. 기존 행을 계산식으로 다시
-- 해석하기 전에 분모, 근태, Escrow와 Wallet 원장을 모두 대사하고, 모순이 하나라도 있으면
-- 첫 DDL 전에 중단합니다. 운영 적용은 애플리케이션과 Scheduler를 멈춘 Maintenance 구간에서만
-- 수행하며 PROCESSING 행을 자동 완료하거나 되돌리지 않습니다.
CREATE TEMPORARY TABLE migration_202608201125_preflight (
    category VARCHAR(96) NOT NULL,
    invalid_count BIGINT UNSIGNED NOT NULL,
    CONSTRAINT ck_migration_202608201125_preflight CHECK (invalid_count = 0)
);

-- 현재 Head의 Settlement lifecycle을 전제로 새 보존 제약을 쌓습니다. 이름만 같은 다른 CHECK나
-- 일부 컬럼만 추가된 형태를 정상으로 간주하지 않습니다.
INSERT INTO migration_202608201125_preflight (category, invalid_count)
SELECT 'settlements_base_lifecycle_shape', IF(
    EXISTS (
        SELECT 1
        FROM information_schema.table_constraints tc
        JOIN information_schema.check_constraints cc
          ON cc.constraint_schema = tc.constraint_schema
         AND CAST(cc.constraint_name AS BINARY) = CAST(tc.constraint_name AS BINARY)
        WHERE tc.constraint_schema = DATABASE()
          AND tc.table_name = 'settlements'
          AND CAST(tc.constraint_name AS BINARY) = CAST('ck_settlements_lifecycle' AS BINARY)
          AND tc.constraint_type = 'CHECK'
          AND tc.enforced = 'YES'
          AND SHA2(cc.check_clause, 256) =
              '63ab283bba29001e37918829d05e48c9b0c749ebc28847c909d0ffdd755b5a79'
    ), 0, 1
);

INSERT INTO migration_202608201125_preflight (category, invalid_count)
SELECT 'settlements_calculation_column_shape', IF(
    (SELECT COUNT(*)
       FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'settlements'
        AND column_name IN (
            'worker_paid_amount', 'owner_refund_amount',
            'deduction_base_minutes', 'late_minutes', 'early_leave_minutes',
            'calculation_reason', 'calculation_version', 'calculated_at'
        )) = 0
    OR (
        EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'settlements'
              AND column_name = 'worker_paid_amount'
              AND column_type = 'bigint unsigned'
              AND is_nullable = 'YES' AND column_default IS NULL
        )
        AND EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'settlements'
              AND column_name = 'owner_refund_amount'
              AND column_type = 'bigint unsigned'
              AND is_nullable = 'YES' AND column_default IS NULL
        )
        AND EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'settlements'
              AND column_name = 'deduction_base_minutes'
              AND column_type = 'bigint unsigned'
              AND is_nullable = 'YES' AND column_default IS NULL
        )
        AND EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'settlements'
              AND column_name = 'late_minutes'
              AND column_type = 'bigint unsigned'
              AND is_nullable = 'YES' AND column_default IS NULL
        )
        AND EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'settlements'
              AND column_name = 'early_leave_minutes'
              AND column_type = 'bigint unsigned'
              AND is_nullable = 'YES' AND column_default IS NULL
        )
        AND EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'settlements'
              AND column_name = 'calculation_reason'
              AND column_type = 'varchar(32)'
              AND character_set_name = 'ascii' AND collation_name = 'ascii_bin'
              AND is_nullable = 'YES' AND column_default IS NULL
        )
        AND EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'settlements'
              AND column_name = 'calculation_version'
              AND column_type = 'varchar(20)'
              AND character_set_name = 'ascii' AND collation_name = 'ascii_bin'
              AND is_nullable = 'YES' AND column_default IS NULL
        )
        AND EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'settlements'
              AND column_name = 'calculated_at'
              AND data_type = 'datetime' AND datetime_precision = 6
              AND is_nullable = 'YES' AND column_default IS NULL
        )
    ), 0, 1
);

-- 한 ALTER TABLE 안에서 추가하는 네 CHECK는 모두 없거나 모두 있어야 합니다. 네 CHECK가 있는
-- 형태는 실패 이력 repair 뒤 재실행하는 경우에만 허용하며, 아래 postflight가 데이터까지 다시
-- 확인합니다.
INSERT INTO migration_202608201125_preflight (category, invalid_count)
SELECT 'settlements_calculation_constraint_set', IF(
    (SELECT COUNT(*)
       FROM information_schema.table_constraints
      WHERE constraint_schema = DATABASE()
        AND table_name = 'settlements'
        AND constraint_name IN (
            'ck_settlements_calculation_snapshot_shape',
            'ck_settlements_calculation_amounts',
            'ck_settlements_calculation_formula',
            'ck_settlements_calculation_lifecycle'
        )) = 0
    OR (
        EXISTS (
            SELECT 1
            FROM information_schema.table_constraints tc
            JOIN information_schema.check_constraints cc
              ON cc.constraint_schema = tc.constraint_schema
             AND CAST(cc.constraint_name AS BINARY) = CAST(tc.constraint_name AS BINARY)
            WHERE tc.constraint_schema = DATABASE() AND tc.table_name = 'settlements'
              AND CAST(tc.constraint_name AS BINARY) =
                  CAST('ck_settlements_calculation_snapshot_shape' AS BINARY)
              AND tc.constraint_type = 'CHECK' AND tc.enforced = 'YES'
              AND SHA2(cc.check_clause, 256) =
                  'a2d0f80ff51043febf23cf440a4cd571011cf0d2823e1a17b77fb1fb5ce54b74'
        )
        AND EXISTS (
            SELECT 1
            FROM information_schema.table_constraints tc
            JOIN information_schema.check_constraints cc
              ON cc.constraint_schema = tc.constraint_schema
             AND CAST(cc.constraint_name AS BINARY) = CAST(tc.constraint_name AS BINARY)
            WHERE tc.constraint_schema = DATABASE() AND tc.table_name = 'settlements'
              AND CAST(tc.constraint_name AS BINARY) =
                  CAST('ck_settlements_calculation_amounts' AS BINARY)
              AND tc.constraint_type = 'CHECK' AND tc.enforced = 'YES'
              AND SHA2(cc.check_clause, 256) =
                  'be50ec04f5b9b84d82ef0b3a77515f77bd9d19e793f3b160730743f86d9144d1'
        )
        AND EXISTS (
            SELECT 1
            FROM information_schema.table_constraints tc
            JOIN information_schema.check_constraints cc
              ON cc.constraint_schema = tc.constraint_schema
             AND CAST(cc.constraint_name AS BINARY) = CAST(tc.constraint_name AS BINARY)
            WHERE tc.constraint_schema = DATABASE() AND tc.table_name = 'settlements'
              AND CAST(tc.constraint_name AS BINARY) =
                  CAST('ck_settlements_calculation_formula' AS BINARY)
              AND tc.constraint_type = 'CHECK' AND tc.enforced = 'YES'
              AND SHA2(cc.check_clause, 256) =
                  '408d843f6e3c5bb2361e7bffec0f38317aa8e817530c46c60b54fe99a012e7b5'
        )
        AND EXISTS (
            SELECT 1
            FROM information_schema.table_constraints tc
            JOIN information_schema.check_constraints cc
              ON cc.constraint_schema = tc.constraint_schema
             AND CAST(cc.constraint_name AS BINARY) = CAST(tc.constraint_name AS BINARY)
            WHERE tc.constraint_schema = DATABASE() AND tc.table_name = 'settlements'
              AND CAST(tc.constraint_name AS BINARY) =
                  CAST('ck_settlements_calculation_lifecycle' AS BINARY)
              AND tc.constraint_type = 'CHECK' AND tc.enforced = 'YES'
              AND SHA2(cc.check_clause, 256) =
                  'f7ab879779932f365d423dcabf1bbe616c5243acca136ad5dbc1d8ed48a94148'
        )
    ), 0, 1
);

INSERT INTO migration_202608201125_preflight (category, invalid_count)
SELECT 'work_case_deduction_constraint_shape', IF(
    NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE() AND table_name = 'work_cases'
          AND CAST(constraint_name AS BINARY) =
              CAST('ck_work_cases_deduction_base_minutes' AS BINARY)
    )
    OR EXISTS (
        SELECT 1
        FROM information_schema.table_constraints tc
        JOIN information_schema.check_constraints cc
          ON cc.constraint_schema = tc.constraint_schema
         AND CAST(cc.constraint_name AS BINARY) = CAST(tc.constraint_name AS BINARY)
        WHERE tc.constraint_schema = DATABASE() AND tc.table_name = 'work_cases'
          AND CAST(tc.constraint_name AS BINARY) =
              CAST('ck_work_cases_deduction_base_minutes' AS BINARY)
          AND tc.constraint_type = 'CHECK' AND tc.enforced = 'YES'
          AND SHA2(cc.check_clause, 256) =
              '005007f724952ccb2a5a8cf0ee783d9b0594442ca48063a1eeb294ac2b9116d6'
    ), 0, 1
);

-- Java의 Duration.toMinutes()와 같은 분 단위 절삭을 예정 분 S로 사용합니다. 유급 휴게는
-- 분모에서 제외하지 않으며, 무급 휴게만 S보다 작아야 합니다.
INSERT INTO migration_202608201125_preflight (category, invalid_count)
SELECT 'invalid_work_case_deduction_base', COUNT(*)
FROM work_cases
WHERE TIMESTAMPDIFF(MINUTE, starts_at, ends_at) <= 0
   OR (
       break_paid = 0
       AND break_minutes >= TIMESTAMPDIFF(MINUTE, starts_at, ends_at)
   );

-- PROCESSING은 자금 Transaction과 별도로 Commit되지 않아야 합니다. 남아 있는 행은 어느 Leg가
-- 실행됐는지 Migration이 판정할 수 없으므로 전부 수동 대사 대상으로 중단합니다.
INSERT INTO migration_202608201125_preflight (category, invalid_count)
SELECT 'processing_settlements', COUNT(*)
FROM settlements
WHERE status = 'PROCESSING';

INSERT INTO migration_202608201125_preflight (category, invalid_count)
SELECT 'settlement_escrow_amount_integrity', COUNT(*)
FROM settlements s
JOIN work_cases wc ON wc.id = s.work_case_id
LEFT JOIN escrows e ON e.work_case_id = s.work_case_id
WHERE wc.employer_id = wc.worker_id
   OR s.amount <> wc.agreed_wage
   OR e.id IS NULL
   OR e.amount <> s.amount;

-- Settlement가 존재하면 최초 예치도 OWNER의 available 감소·locked 증가 원장 한 건으로
-- 증명되어야 합니다. 이 증거가 없으면 이후 지급·환불 Leg만으로 원 예치액을 추정하지 않습니다.
INSERT INTO migration_202608201125_preflight (category, invalid_count)
SELECT 'escrow_hold_ledger_integrity', COUNT(*)
FROM settlements s
JOIN work_cases wc ON wc.id = s.work_case_id
JOIN escrows e ON e.work_case_id = s.work_case_id
WHERE (
    SELECT COUNT(*)
    FROM wallet_transactions wt
    JOIN wallets w ON w.id = wt.wallet_id
    WHERE wt.work_case_id = s.work_case_id
      AND w.user_id = wc.employer_id
      AND wt.transaction_type = 'ESCROW_HOLD'
      AND wt.amount = s.amount
      AND wt.reference_type = 'ESCROW' AND wt.reference_id = e.id
      AND wt.available_before >= wt.amount
      AND wt.available_after = wt.available_before - wt.amount
      AND wt.locked_after >= wt.locked_before
      AND wt.locked_after - wt.locked_before = wt.amount
) <> 1;

-- 아직 자금을 실행하지 않은 상태에는 HELD Escrow만 있고 release/refund Leg가 없어야 합니다.
INSERT INTO migration_202608201125_preflight (category, invalid_count)
SELECT 'unmoved_settlement_fund_integrity', COUNT(*)
FROM settlements s
JOIN escrows e ON e.work_case_id = s.work_case_id
WHERE s.status IN ('WAITING', 'SCHEDULED', 'ON_HOLD', 'FAILED')
  AND (
      e.status <> 'HELD'
      OR EXISTS (
          SELECT 1
          FROM wallet_transactions wt
          WHERE wt.work_case_id = s.work_case_id
            AND wt.transaction_type IN ('ESCROW_RELEASE', 'ESCROW_REFUND')
      )
  );

-- 지급 대기·보류·실패 행은 완료 근무와 성공 출퇴근 한 쌍으로만 새 공식을 복원할 수 있습니다.
INSERT INTO migration_202608201125_preflight (category, invalid_count)
SELECT 'checked_out_snapshot_source_integrity', COUNT(*)
FROM settlements s
JOIN work_cases wc ON wc.id = s.work_case_id
WHERE s.status IN ('SCHEDULED', 'ON_HOLD', 'FAILED')
  AND (
      wc.status <> 'COMPLETED'
      OR (
          SELECT COUNT(*) FROM attendance_records ar
          WHERE ar.work_case_id = s.work_case_id
            AND ar.attendance_type = 'CHECK_IN' AND ar.result = 'SUCCESS'
      ) <> 1
      OR (
          SELECT COUNT(*) FROM attendance_records ar
          WHERE ar.work_case_id = s.work_case_id
            AND ar.attendance_type = 'CHECK_OUT' AND ar.result = 'SUCCESS'
      ) <> 1
  );

-- WAITING은 아직 진행 중인 근무이거나, 환불 승인 전 NO_SHOW/CHECK_OUT_MISSING이어야 합니다.
-- COMPLETED·CANCELED·DRAFT와 결합된 WAITING은 정산 의도를 추정하지 않고 중단합니다.
INSERT INTO migration_202608201125_preflight (category, invalid_count)
SELECT 'waiting_settlement_work_state_integrity', COUNT(*)
FROM settlements s
JOIN work_cases wc ON wc.id = s.work_case_id
WHERE s.status = 'WAITING'
  AND wc.status NOT IN (
      'ACCEPTED', 'READY', 'IN_PROGRESS', 'NO_SHOW', 'CHECK_OUT_MISSING'
  );

INSERT INTO migration_202608201125_preflight (category, invalid_count)
SELECT 'no_show_snapshot_source_integrity', COUNT(*)
FROM settlements s
JOIN work_cases wc ON wc.id = s.work_case_id
WHERE s.status = 'WAITING' AND wc.status = 'NO_SHOW'
  AND (
      (
          SELECT COUNT(*) FROM attendance_records ar
          WHERE ar.work_case_id = s.work_case_id
            AND ar.attendance_type = 'CHECK_IN' AND ar.result = 'SUCCESS'
      ) <> 0
      OR (
          SELECT COUNT(*) FROM attendance_records ar
          WHERE ar.work_case_id = s.work_case_id
            AND ar.attendance_type = 'CHECK_OUT' AND ar.result = 'SUCCESS'
      ) <> 0
  );

INSERT INTO migration_202608201125_preflight (category, invalid_count)
SELECT 'check_out_missing_snapshot_source_integrity', COUNT(*)
FROM settlements s
JOIN work_cases wc ON wc.id = s.work_case_id
WHERE s.status = 'WAITING' AND wc.status = 'CHECK_OUT_MISSING'
  AND (
      (
          SELECT COUNT(*) FROM attendance_records ar
          WHERE ar.work_case_id = s.work_case_id
            AND ar.attendance_type = 'CHECK_IN' AND ar.result = 'SUCCESS'
      ) <> 1
      OR (
          SELECT COUNT(*) FROM attendance_records ar
          WHERE ar.work_case_id = s.work_case_id
            AND ar.attendance_type = 'CHECK_OUT' AND ar.result = 'SUCCESS'
      ) <> 0
  );

-- 기존 COMPLETED는 전액 지급 계약의 실제 원장을 증명한 뒤 LEGACY A/0으로만 보존합니다.
INSERT INTO migration_202608201125_preflight (category, invalid_count)
SELECT 'legacy_completed_fund_integrity', COUNT(*)
FROM settlements s
JOIN work_cases wc ON wc.id = s.work_case_id
LEFT JOIN escrows e ON e.work_case_id = s.work_case_id
WHERE s.status = 'COMPLETED'
  AND (
      e.id IS NULL OR e.status <> 'RELEASED' OR e.amount <> s.amount
      OR (
          SELECT COUNT(*)
          FROM wallet_transactions wt
          WHERE wt.work_case_id = s.work_case_id
            AND wt.transaction_type IN ('ESCROW_RELEASE', 'ESCROW_REFUND')
      ) <> 2
      OR (
          SELECT COUNT(*)
          FROM wallet_transactions wt
          JOIN wallets w ON w.id = wt.wallet_id
          WHERE wt.work_case_id = s.work_case_id
            AND w.user_id = wc.employer_id
            AND wt.transaction_type = 'ESCROW_RELEASE'
            AND wt.amount = s.amount
            AND wt.reference_type = 'ESCROW' AND wt.reference_id = e.id
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
            AND wt.reference_type = 'ESCROW' AND wt.reference_id = e.id
            AND wt.available_after >= wt.available_before
            AND wt.available_after - wt.available_before = wt.amount
            AND wt.locked_before = wt.locked_after
      ) <> 1
  );

-- 기존 REFUNDED는 OWNER 전액 환불 Leg 하나와 WORKER 무변경을 증명합니다.
INSERT INTO migration_202608201125_preflight (category, invalid_count)
SELECT 'legacy_refunded_fund_integrity', COUNT(*)
FROM settlements s
JOIN work_cases wc ON wc.id = s.work_case_id
LEFT JOIN escrows e ON e.work_case_id = s.work_case_id
WHERE s.status = 'REFUNDED'
  AND (
      e.id IS NULL OR e.status <> 'REFUNDED' OR e.amount <> s.amount
      OR (
          SELECT COUNT(*)
          FROM wallet_transactions wt
          WHERE wt.work_case_id = s.work_case_id
            AND wt.transaction_type IN ('ESCROW_RELEASE', 'ESCROW_REFUND')
      ) <> 1
      OR (
          SELECT COUNT(*)
          FROM wallet_transactions wt
          JOIN wallets w ON w.id = wt.wallet_id
          WHERE wt.work_case_id = s.work_case_id
            AND w.user_id = wc.employer_id
            AND wt.transaction_type = 'ESCROW_REFUND'
            AND wt.amount = s.amount
            AND wt.reference_type = 'ESCROW' AND wt.reference_id = e.id
            AND wt.available_after >= wt.available_before
            AND wt.available_after - wt.available_before = wt.amount
            AND wt.locked_before >= wt.amount
            AND wt.locked_after = wt.locked_before - wt.amount
      ) <> 1
      OR EXISTS (
          SELECT 1
          FROM wallet_transactions wt
          JOIN wallets w ON w.id = wt.wallet_id
          WHERE wt.work_case_id = s.work_case_id
            AND w.user_id = wc.worker_id
            AND wt.transaction_type IN ('ESCROW_RELEASE', 'ESCROW_REFUND')
      )
  );

DROP TEMPORARY TABLE migration_202608201125_preflight;

-- 컬럼 추가 ALTER는 한 문장으로 원자 적용합니다. 실패 이력 repair 뒤 정확한 8컬럼 형태가
-- 이미 있으면 건너뛰고 Backfill·CHECK 검증부터 다시 수행합니다.
SET @migration_202608201125_ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'settlements'
          AND column_name = 'worker_paid_amount'
    ),
    'DO 0',
    'ALTER TABLE settlements'
        ' ADD COLUMN worker_paid_amount BIGINT UNSIGNED NULL AFTER amount,'
        ' ADD COLUMN owner_refund_amount BIGINT UNSIGNED NULL AFTER worker_paid_amount,'
        ' ADD COLUMN deduction_base_minutes BIGINT UNSIGNED NULL AFTER owner_refund_amount,'
        ' ADD COLUMN late_minutes BIGINT UNSIGNED NULL AFTER deduction_base_minutes,'
        ' ADD COLUMN early_leave_minutes BIGINT UNSIGNED NULL AFTER late_minutes,'
        ' ADD COLUMN calculation_reason VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER early_leave_minutes,'
        ' ADD COLUMN calculation_version VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER calculation_reason,'
        ' ADD COLUMN calculated_at DATETIME(6) NULL AFTER calculation_version'
);
PREPARE migration_statement FROM @migration_202608201125_ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_202608201125_calculated_at = NOW(6);

CREATE TEMPORARY TABLE migration_202608201125_backfill (
    settlement_id BIGINT UNSIGNED NOT NULL,
    worker_paid_amount BIGINT UNSIGNED NOT NULL,
    owner_refund_amount BIGINT UNSIGNED NOT NULL,
    deduction_base_minutes BIGINT UNSIGNED NULL,
    late_minutes BIGINT UNSIGNED NULL,
    early_leave_minutes BIGINT UNSIGNED NULL,
    calculation_reason VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    calculation_version VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    calculated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (settlement_id)
);

INSERT INTO migration_202608201125_backfill (
    settlement_id, worker_paid_amount, owner_refund_amount,
    deduction_base_minutes, late_minutes, early_leave_minutes,
    calculation_reason, calculation_version, calculated_at
)
SELECT s.id,
       CASE WHEN s.status = 'COMPLETED' THEN s.amount ELSE 0 END,
       CASE WHEN s.status = 'REFUNDED' THEN s.amount ELSE 0 END,
       NULL, NULL, NULL,
       'LEGACY', 'LEGACY',
       COALESCE(s.calculated_at, @migration_202608201125_calculated_at)
FROM settlements s
WHERE s.status IN ('COMPLETED', 'REFUNDED');

-- 성공 CHECK_OUT Snapshot. P 계산에는 DECIMAL(65,0)을 사용해 약정액과 분수의 곱셈이
-- BIGINT 중간값에서 overflow하지 않도록 합니다.
INSERT INTO migration_202608201125_backfill (
    settlement_id, worker_paid_amount, owner_refund_amount,
    deduction_base_minutes, late_minutes, early_leave_minutes,
    calculation_reason, calculation_version, calculated_at
)
SELECT calculated.settlement_id,
       calculated.worker_paid_amount,
       calculated.amount - calculated.worker_paid_amount,
       calculated.deduction_base_minutes,
       calculated.late_minutes,
       calculated.early_leave_minutes,
       'CHECKED_OUT', 'ATTENDANCE_V1', calculated.calculated_at
FROM (
    SELECT inputs.*,
           CASE
               WHEN inputs.late_minutes = 0 AND inputs.early_leave_minutes = 0
                   THEN inputs.amount
               ELSE CAST(
                   FLOOR(
                       CAST(inputs.amount AS DECIMAL(65, 0))
                       * (
                           CAST(inputs.deduction_base_minutes AS DECIMAL(65, 0))
                           - LEAST(
                               CAST(inputs.deduction_base_minutes AS DECIMAL(65, 0)),
                               CAST(inputs.late_minutes AS DECIMAL(65, 0))
                               + CAST(inputs.early_leave_minutes AS DECIMAL(65, 0))
                           )
                       )
                       / CAST(inputs.deduction_base_minutes AS DECIMAL(65, 0))
                       / 10
                   ) * 10 AS UNSIGNED
               )
           END AS worker_paid_amount
    FROM (
        SELECT s.id AS settlement_id,
               s.amount,
               CAST(
                   TIMESTAMPDIFF(MINUTE, wc.starts_at, wc.ends_at)
                   - IF(wc.break_paid = 0, wc.break_minutes, 0)
                   AS UNSIGNED
               ) AS deduction_base_minutes,
               CAST(
                   CEIL(
                       GREATEST(
                           TIMESTAMPDIFF(MICROSECOND, wc.starts_at, check_in.attempted_at),
                           0
                       ) / 60000000
                   ) AS UNSIGNED
               ) AS late_minutes,
               CAST(
                   CEIL(
                       GREATEST(
                           TIMESTAMPDIFF(MICROSECOND, check_out.attempted_at, wc.ends_at),
                           0
                       ) / 60000000
                   ) AS UNSIGNED
               ) AS early_leave_minutes,
               COALESCE(s.calculated_at, @migration_202608201125_calculated_at)
                   AS calculated_at
        FROM settlements s
        JOIN work_cases wc ON wc.id = s.work_case_id
        JOIN attendance_records check_in
          ON check_in.work_case_id = s.work_case_id
         AND check_in.attendance_type = 'CHECK_IN'
         AND check_in.result = 'SUCCESS'
        JOIN attendance_records check_out
          ON check_out.work_case_id = s.work_case_id
         AND check_out.attendance_type = 'CHECK_OUT'
         AND check_out.result = 'SUCCESS'
        WHERE s.status IN ('SCHEDULED', 'ON_HOLD', 'FAILED')
    ) inputs
) calculated;

INSERT INTO migration_202608201125_backfill (
    settlement_id, worker_paid_amount, owner_refund_amount,
    deduction_base_minutes, late_minutes, early_leave_minutes,
    calculation_reason, calculation_version, calculated_at
)
SELECT s.id, 0, s.amount,
       CAST(
           TIMESTAMPDIFF(MINUTE, wc.starts_at, wc.ends_at)
           - IF(wc.break_paid = 0, wc.break_minutes, 0)
           AS UNSIGNED
       ),
       0, 0,
       'NO_SHOW', 'ATTENDANCE_V1',
       COALESCE(s.calculated_at, @migration_202608201125_calculated_at)
FROM settlements s
JOIN work_cases wc ON wc.id = s.work_case_id
WHERE s.status = 'WAITING' AND wc.status = 'NO_SHOW';

INSERT INTO migration_202608201125_backfill (
    settlement_id, worker_paid_amount, owner_refund_amount,
    deduction_base_minutes, late_minutes, early_leave_minutes,
    calculation_reason, calculation_version, calculated_at
)
SELECT s.id, 0, s.amount,
       CAST(
           TIMESTAMPDIFF(MINUTE, wc.starts_at, wc.ends_at)
           - IF(wc.break_paid = 0, wc.break_minutes, 0)
           AS UNSIGNED
       ),
       CAST(
           CEIL(
               GREATEST(
                   TIMESTAMPDIFF(MICROSECOND, wc.starts_at, check_in.attempted_at),
                   0
               ) / 60000000
           ) AS UNSIGNED
       ),
       0,
       'CHECK_OUT_MISSING', 'ATTENDANCE_V1',
       COALESCE(s.calculated_at, @migration_202608201125_calculated_at)
FROM settlements s
JOIN work_cases wc ON wc.id = s.work_case_id
JOIN attendance_records check_in
  ON check_in.work_case_id = s.work_case_id
 AND check_in.attendance_type = 'CHECK_IN'
 AND check_in.result = 'SUCCESS'
WHERE s.status = 'WAITING' AND wc.status = 'CHECK_OUT_MISSING';

-- 이미 완전 Backfill된 행은 재실행 때 그대로 두고, 8필드가 모두 NULL인 행만 한 문장으로
-- 채웁니다. updated_at을 명시적으로 자기 값에 할당해 과거 업무 시각을 Backfill 시각으로
-- 덮어쓰지 않습니다.
UPDATE settlements s
JOIN migration_202608201125_backfill b ON b.settlement_id = s.id
SET s.worker_paid_amount = b.worker_paid_amount,
    s.owner_refund_amount = b.owner_refund_amount,
    s.deduction_base_minutes = b.deduction_base_minutes,
    s.late_minutes = b.late_minutes,
    s.early_leave_minutes = b.early_leave_minutes,
    s.calculation_reason = b.calculation_reason,
    s.calculation_version = b.calculation_version,
    s.calculated_at = b.calculated_at,
    s.updated_at = s.updated_at
WHERE s.worker_paid_amount IS NULL
  AND s.owner_refund_amount IS NULL
  AND s.deduction_base_minutes IS NULL
  AND s.late_minutes IS NULL
  AND s.early_leave_minutes IS NULL
  AND s.calculation_reason IS NULL
  AND s.calculation_version IS NULL
  AND s.calculated_at IS NULL;

CREATE TEMPORARY TABLE migration_202608201125_postflight (
    category VARCHAR(96) NOT NULL,
    invalid_count BIGINT UNSIGNED NOT NULL,
    CONSTRAINT ck_migration_202608201125_postflight CHECK (invalid_count = 0)
);

INSERT INTO migration_202608201125_postflight (category, invalid_count)
SELECT 'backfill_result_mismatch', COUNT(*)
FROM migration_202608201125_backfill b
JOIN settlements s ON s.id = b.settlement_id
WHERE NOT (
    s.worker_paid_amount <=> b.worker_paid_amount
    AND s.owner_refund_amount <=> b.owner_refund_amount
    AND s.deduction_base_minutes <=> b.deduction_base_minutes
    AND s.late_minutes <=> b.late_minutes
    AND s.early_leave_minutes <=> b.early_leave_minutes
    AND s.calculation_reason <=> b.calculation_reason
    AND s.calculation_version <=> b.calculation_version
    AND s.calculated_at <=> b.calculated_at
);

INSERT INTO migration_202608201125_postflight (category, invalid_count)
SELECT 'non_waiting_snapshot_missing', COUNT(*)
FROM settlements
WHERE status <> 'WAITING' AND calculated_at IS NULL;

INSERT INTO migration_202608201125_postflight (category, invalid_count)
SELECT 'waiting_snapshot_coverage', COUNT(*)
FROM settlements s
JOIN work_cases wc ON wc.id = s.work_case_id
WHERE s.status = 'WAITING'
  AND (
      (wc.status IN ('NO_SHOW', 'CHECK_OUT_MISSING') AND s.calculated_at IS NULL)
      OR (
          wc.status IN ('ACCEPTED', 'READY', 'IN_PROGRESS')
          AND (
              s.worker_paid_amount IS NOT NULL
              OR s.owner_refund_amount IS NOT NULL
              OR s.deduction_base_minutes IS NOT NULL
              OR s.late_minutes IS NOT NULL
              OR s.early_leave_minutes IS NOT NULL
              OR s.calculation_reason IS NOT NULL
              OR s.calculation_version IS NOT NULL
              OR s.calculated_at IS NOT NULL
          )
      )
  );

-- CHECK 추가 전에 같은 식으로 모든 Backfill 결과를 재검증합니다.
INSERT INTO migration_202608201125_postflight (category, invalid_count)
SELECT 'snapshot_shape_or_amount_invalid', COUNT(*)
FROM settlements
WHERE NOT (
    (
        worker_paid_amount IS NULL
        AND owner_refund_amount IS NULL
        AND deduction_base_minutes IS NULL
        AND late_minutes IS NULL
        AND early_leave_minutes IS NULL
        AND calculation_reason IS NULL
        AND calculation_version IS NULL
        AND calculated_at IS NULL
    )
    OR (
        worker_paid_amount IS NOT NULL
        AND owner_refund_amount IS NOT NULL
        AND calculation_reason IS NOT NULL
        AND calculation_version IS NOT NULL
        AND calculated_at IS NOT NULL
        AND worker_paid_amount <= amount
        AND owner_refund_amount = amount - worker_paid_amount
        AND (
            (
                calculation_reason = 'LEGACY'
                AND calculation_version = 'LEGACY'
                AND deduction_base_minutes IS NULL
                AND late_minutes IS NULL
                AND early_leave_minutes IS NULL
                AND status IN ('COMPLETED', 'REFUNDED')
            )
            OR (
                calculation_version = 'ATTENDANCE_V1'
                AND deduction_base_minutes IS NOT NULL
                AND deduction_base_minutes > 0
                AND late_minutes IS NOT NULL
                AND early_leave_minutes IS NOT NULL
                AND (
                    (
                        calculation_reason = 'CHECKED_OUT'
                        AND status IN (
                            'SCHEDULED', 'ON_HOLD', 'PROCESSING',
                            'COMPLETED', 'FAILED'
                        )
                    )
                    OR (
                        calculation_reason IN ('NO_SHOW', 'CHECK_OUT_MISSING')
                        AND status IN ('WAITING', 'PROCESSING', 'REFUNDED')
                    )
                )
            )
        )
    )
);

INSERT INTO migration_202608201125_postflight (category, invalid_count)
SELECT 'snapshot_formula_invalid', COUNT(*)
FROM settlements
WHERE calculation_reason IS NOT NULL
  AND NOT (
      calculation_reason = 'LEGACY'
      OR (
          calculation_reason IN ('NO_SHOW', 'CHECK_OUT_MISSING')
          AND worker_paid_amount = 0
          AND owner_refund_amount = amount
      )
      OR (
          calculation_reason = 'CHECKED_OUT'
          AND worker_paid_amount = CASE
              WHEN late_minutes = 0 AND early_leave_minutes = 0 THEN amount
              ELSE CAST(
                  FLOOR(
                      CAST(amount AS DECIMAL(65, 0))
                      * (
                          CAST(deduction_base_minutes AS DECIMAL(65, 0))
                          - LEAST(
                              CAST(deduction_base_minutes AS DECIMAL(65, 0)),
                              CAST(late_minutes AS DECIMAL(65, 0))
                              + CAST(early_leave_minutes AS DECIMAL(65, 0))
                          )
                      )
                      / CAST(deduction_base_minutes AS DECIMAL(65, 0))
                      / 10
                  ) * 10 AS UNSIGNED
              )
          END
      )
  );

DROP TEMPORARY TABLE migration_202608201125_postflight;
DROP TEMPORARY TABLE migration_202608201125_backfill;

-- 네 CHECK를 한 ALTER 안에서 원자 적용합니다. Snapshot 식은 상태·Shape·보존식·공식을 각각
-- 이름으로 분리해 운영 진단에서 어떤 불변식이 깨졌는지 드러냅니다.
SET @migration_202608201125_ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE() AND table_name = 'settlements'
          AND constraint_name = 'ck_settlements_calculation_snapshot_shape'
    ),
    'DO 0',
    'ALTER TABLE settlements'
        ' ADD CONSTRAINT ck_settlements_calculation_snapshot_shape CHECK ('
        ' (worker_paid_amount IS NULL AND owner_refund_amount IS NULL AND deduction_base_minutes IS NULL AND late_minutes IS NULL AND early_leave_minutes IS NULL AND calculation_reason IS NULL AND calculation_version IS NULL AND calculated_at IS NULL)'
        ' OR (worker_paid_amount IS NOT NULL AND owner_refund_amount IS NOT NULL AND calculation_reason IS NOT NULL AND calculation_version IS NOT NULL AND calculated_at IS NOT NULL AND ('
        ' (calculation_reason = ''LEGACY'' AND calculation_version = ''LEGACY'' AND deduction_base_minutes IS NULL AND late_minutes IS NULL AND early_leave_minutes IS NULL AND status IN (''COMPLETED'', ''REFUNDED''))'
        ' OR (calculation_version = ''ATTENDANCE_V1'' AND deduction_base_minutes IS NOT NULL AND deduction_base_minutes > 0 AND late_minutes IS NOT NULL AND early_leave_minutes IS NOT NULL AND ('
        ' (calculation_reason = ''CHECKED_OUT'' AND status IN (''SCHEDULED'', ''ON_HOLD'', ''PROCESSING'', ''COMPLETED'', ''FAILED''))'
        ' OR (calculation_reason IN (''NO_SHOW'', ''CHECK_OUT_MISSING'') AND status IN (''WAITING'', ''PROCESSING'', ''REFUNDED''))'
        ' ))'
        ' )'
        ' )'
        ' ),'
        ' ADD CONSTRAINT ck_settlements_calculation_amounts CHECK ('
        ' worker_paid_amount IS NULL OR (worker_paid_amount <= amount AND owner_refund_amount = amount - worker_paid_amount)'
        ' ),'
        ' ADD CONSTRAINT ck_settlements_calculation_formula CHECK ('
        ' calculation_reason IS NULL OR calculation_reason = ''LEGACY'''
        ' OR (calculation_reason IN (''NO_SHOW'', ''CHECK_OUT_MISSING'') AND worker_paid_amount = 0 AND owner_refund_amount = amount)'
        ' OR (calculation_reason = ''CHECKED_OUT'' AND worker_paid_amount = CASE'
        ' WHEN late_minutes = 0 AND early_leave_minutes = 0 THEN amount'
        ' ELSE CAST(FLOOR(CAST(amount AS DECIMAL(65, 0)) * (CAST(deduction_base_minutes AS DECIMAL(65, 0)) - LEAST(CAST(deduction_base_minutes AS DECIMAL(65, 0)), CAST(late_minutes AS DECIMAL(65, 0)) + CAST(early_leave_minutes AS DECIMAL(65, 0)))) / CAST(deduction_base_minutes AS DECIMAL(65, 0)) / 10) * 10 AS UNSIGNED) END)'
        ' ),'
        ' ADD CONSTRAINT ck_settlements_calculation_lifecycle CHECK ('
        ' status = ''WAITING'' OR calculated_at IS NOT NULL'
        ' )'
);
PREPARE migration_statement FROM @migration_202608201125_ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_202608201125_ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE() AND table_name = 'work_cases'
          AND constraint_name = 'ck_work_cases_deduction_base_minutes'
    ),
    'DO 0',
    'ALTER TABLE work_cases'
        ' ADD CONSTRAINT ck_work_cases_deduction_base_minutes CHECK ('
        ' TIMESTAMPDIFF(MINUTE, starts_at, ends_at) > 0'
        ' AND (break_paid = 1 OR break_minutes < TIMESTAMPDIFF(MINUTE, starts_at, ends_at))'
        ' )'
);
PREPARE migration_statement FROM @migration_202608201125_ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
