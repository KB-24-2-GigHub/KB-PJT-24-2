SET @owner_total = @owner_available + @owner_locked;

INSERT INTO wallet_transactions (
    wallet_id, work_case_id, transaction_type, amount,
    available_before, available_after, locked_before, locked_after,
    reference_type, reference_id, idempotency_key, created_at
) VALUES
    (
        @owner_wallet_id, NULL, 'FUNDING', @owner_total,
        0, @owner_total, 0, 0,
        'DEMO_SEED', @owner_id, 'DEMO-OWNER-FUNDING', DATE_SUB(@accepted_at, INTERVAL 1 MINUTE)
    ),
    (
        @owner_wallet_id, @work_case_a_id, 'ESCROW_HOLD', @demo_wage,
        @owner_total, @owner_total - @demo_wage, 0, @demo_wage,
        'ESCROW', @escrow_a_id, 'DEMO-A-HOLD', @accepted_at
    ),
    (
        @owner_wallet_id, @work_case_b_id, 'ESCROW_HOLD', @demo_wage,
        @owner_total - @demo_wage, @owner_total - (@demo_wage * 2),
        @demo_wage, @demo_wage * 2,
        'ESCROW', @escrow_b_id, 'DEMO-B-HOLD', DATE_ADD(@accepted_at, INTERVAL 1 SECOND)
    ),
    (
        @owner_wallet_id, @work_case_c_id, 'ESCROW_HOLD', @demo_wage,
        @owner_total - (@demo_wage * 2), @owner_total - (@demo_wage * 3),
        @demo_wage * 2, @demo_wage * 3,
        'ESCROW', @escrow_c_id, 'DEMO-C-HOLD', DATE_ADD(@accepted_at, INTERVAL 2 SECOND)
    );
