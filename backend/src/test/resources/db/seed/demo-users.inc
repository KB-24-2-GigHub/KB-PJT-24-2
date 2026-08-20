-- demo-*.sql이 설정해야 하는 금액 변수:
-- @owner_available, @owner_locked, @owner_bank_balance,
-- @worker_a_available, @worker_b_available, @worker_c_available

SET @demo_password_hash = '$2b$12$swzyk8RjBYPEQbfBnnXATuCWSILA9USCTXllUZNbwMV6JP1vEZ1VS';

INSERT INTO users (
    login_id, email, password_hash, name, phone, role, status,
    created_at, updated_at
) VALUES
    (
        'gigsajang', 'gigsajang@example.invalid', @demo_password_hash,
        '긱사장', '01000001001', 'OWNER', 'ACTIVE', @seed_now, @seed_now
    ),
    (
        'hardworker', 'hardworker@example.invalid', @demo_password_hash,
        '김성실', '01000001002', 'WORKER', 'ACTIVE', @seed_now, @seed_now
    ),
    (
        'ilovesleep', 'ilovesleep@example.invalid', @demo_password_hash,
        '이수면', '01000001003', 'WORKER', 'ACTIVE', @seed_now, @seed_now
    ),
    (
        'submarine', 'submarine@example.invalid', @demo_password_hash,
        '박잠수', '01000001004', 'WORKER', 'ACTIVE', @seed_now, @seed_now
    );

SET @owner_id = (SELECT id FROM users WHERE login_id = 'gigsajang');
SET @worker_a_id = (SELECT id FROM users WHERE login_id = 'hardworker');
SET @worker_b_id = (SELECT id FROM users WHERE login_id = 'ilovesleep');
SET @worker_c_id = (SELECT id FROM users WHERE login_id = 'submarine');

INSERT INTO wallets (user_id, currency, available_balance, locked_balance)
VALUES
    (@owner_id, 'KRW', @owner_available, @owner_locked),
    (@worker_a_id, 'KRW', @worker_a_available, 0),
    (@worker_b_id, 'KRW', @worker_b_available, 0),
    (@worker_c_id, 'KRW', @worker_c_available, 0);

SET @owner_wallet_id = (
    SELECT id FROM wallets WHERE user_id = @owner_id AND currency = 'KRW'
);
SET @worker_a_wallet_id = (
    SELECT id FROM wallets WHERE user_id = @worker_a_id AND currency = 'KRW'
);
SET @worker_b_wallet_id = (
    SELECT id FROM wallets WHERE user_id = @worker_b_id AND currency = 'KRW'
);
SET @worker_c_wallet_id = (
    SELECT id FROM wallets WHERE user_id = @worker_c_id AND currency = 'KRW'
);

INSERT INTO mock_bank_accounts (
    bank_code, mock_account_number, pin, mock_fintech_use_num,
    currency, balance, available_amount, status,
    created_at, updated_at
) VALUES
    (
        '004', '110000001001', '0000', 'DEMO-FINTECH-OWNER',
        'KRW', @owner_bank_balance, @owner_bank_balance, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '004', '110000001002', '0000', 'DEMO-FINTECH-WORKER-A',
        'KRW', 0, 0, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '004', '110000001003', '0000', 'DEMO-FINTECH-WORKER-B',
        'KRW', 0, 0, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '004', '110000001004', '0000', 'DEMO-FINTECH-WORKER-C',
        'KRW', 0, 0, 'ACTIVE', @seed_now, @seed_now
    );
