-- demo-*.sql이 설정해야 하는 금액 변수:
-- @owner_available, @owner_locked, @owner_bank_balance,
-- @worker_a_available, @worker_b_available, @worker_c_available

SET @demo_password_hash = '$2b$12$swzyk8RjBYPEQbfBnnXATuCWSILA9USCTXllUZNbwMV6JP1vEZ1VS';
SET @demo_other_bank_balance = 5000000;

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
        '004', '27890104563218', '4827', 'DEMO-FINTECH-OWNER',
        'KRW', @owner_bank_balance, @owner_bank_balance, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '004', '48721504938621', '6153', 'DEMO-FINTECH-WORKER-A',
        'KRW', @demo_other_bank_balance, @demo_other_bank_balance, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '004', '63190404275183', '2948', 'DEMO-FINTECH-WORKER-B',
        'KRW', @demo_other_bank_balance, @demo_other_bank_balance, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '004', '85432704619742', '7306', 'DEMO-FINTECH-WORKER-C',
        'KRW', @demo_other_bank_balance, @demo_other_bank_balance, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '088', '110542893761', '8514', 'DEMO-FINTECH-SHINHAN',
        'KRW', @demo_other_bank_balance, @demo_other_bank_balance, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '020', '1002845731926', '4062', 'DEMO-FINTECH-WOORI',
        'KRW', @demo_other_bank_balance, @demo_other_bank_balance, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '081', '39191048273654', '9731', 'DEMO-FINTECH-HANA',
        'KRW', @demo_other_bank_balance, @demo_other_bank_balance, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '011', '3021847629153', '5286', 'DEMO-FINTECH-NH',
        'KRW', @demo_other_bank_balance, @demo_other_bank_balance, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '003', '21409753104826', '1479', 'DEMO-FINTECH-IBK',
        'KRW', @demo_other_bank_balance, @demo_other_bank_balance, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '090', '3333184729561', '6832', 'DEMO-FINTECH-KAKAO',
        'KRW', @demo_other_bank_balance, @demo_other_bank_balance, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '092', '100078429516', '9057', 'DEMO-FINTECH-TOSS',
        'KRW', @demo_other_bank_balance, @demo_other_bank_balance, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '089', '100287453619', '3641', 'DEMO-FINTECH-KBANK',
        'KRW', @demo_other_bank_balance, @demo_other_bank_balance, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '032', '1132458739104', '7925', 'DEMO-FINTECH-BUSAN',
        'KRW', @demo_other_bank_balance, @demo_other_bank_balance, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '031', '50814729365', '2186', 'DEMO-FINTECH-DGB',
        'KRW', @demo_other_bank_balance, @demo_other_bank_balance, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '131', '50821846397', '6749', 'DEMO-FINTECH-IM',
        'KRW', @demo_other_bank_balance, @demo_other_bank_balance, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '034', '110753842691', '4318', 'DEMO-FINTECH-GWANGJU',
        'KRW', @demo_other_bank_balance, @demo_other_bank_balance, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '023', '2213864592', '5863', 'DEMO-FINTECH-SC',
        'KRW', @demo_other_bank_balance, @demo_other_bank_balance, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '027', '2875639104', '9274', 'DEMO-FINTECH-CITI',
        'KRW', @demo_other_bank_balance, @demo_other_bank_balance, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '002', '0137584269104', '3058', 'DEMO-FINTECH-KDB',
        'KRW', @demo_other_bank_balance, @demo_other_bank_balance, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '007', '101028467395', '8642', 'DEMO-FINTECH-SUHYUP',
        'KRW', @demo_other_bank_balance, @demo_other_bank_balance, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '045', '9002184736295', '1597', 'DEMO-FINTECH-MG',
        'KRW', @demo_other_bank_balance, @demo_other_bank_balance, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '048', '1310294758612', '7483', 'DEMO-FINTECH-CREDIT-UNION',
        'KRW', @demo_other_bank_balance, @demo_other_bank_balance, 'ACTIVE', @seed_now, @seed_now
    ),
    (
        '071', '01234502847396', '2369', 'DEMO-FINTECH-POST',
        'KRW', @demo_other_bank_balance, @demo_other_bank_balance, 'ACTIVE', @seed_now, @seed_now
    );
