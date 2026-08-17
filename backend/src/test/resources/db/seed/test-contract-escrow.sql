SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SET time_zone = '+09:00';

START TRANSACTION;

SET @seed_title = '[TEST-17] 합성 근로계약서 에스크로 테스트';
SET @password_hash = '$2a$12$B.pEu4sY.xoGiLOP1m5dPuuc1xctCXGm1Cwe85k/.yBMefiZigpqq';
SET @accepted_at = '2026-07-22 13:00:00.000000';

INSERT INTO users (
    login_id, email, password_hash, name, phone, role, status
) VALUES (
    'test_owner_17', 'test-owner-17@example.invalid', @password_hash,
    '테스트 사장', '01000000017', 'OWNER', 'ACTIVE'
)
ON DUPLICATE KEY UPDATE
    password_hash = @password_hash,
    name = '테스트 사장',
    phone = '01000000017',
    role = 'OWNER',
    status = 'ACTIVE',
    deleted_at = NULL;

INSERT INTO users (
    login_id, email, password_hash, name, phone, role, status
) VALUES (
    'test_worker_17', 'test-worker-17@example.invalid', @password_hash,
    '테스트 근로자', '01000001017', 'WORKER', 'ACTIVE'
)
ON DUPLICATE KEY UPDATE
    password_hash = @password_hash,
    name = '테스트 근로자',
    phone = '01000001017',
    role = 'WORKER',
    status = 'ACTIVE',
    deleted_at = NULL;

SET @owner_id = (
    SELECT id FROM users WHERE login_id = 'test_owner_17'
);
SET @worker_id = (
    SELECT id FROM users WHERE login_id = 'test_worker_17'
);

INSERT INTO workplaces (
    owner_user_id, business_registration_number, name,
    representative_name, road_address, detail_address, phone,
    latitude, longitude, radius_meters, status
) VALUES (
    @owner_id, '0000000017', 'Gig-Hub 합성 테스트 매장',
    '테스트 사장', '서울 광진구 능동로 195-16', NULL, '0200000017',
    37.5481384, 127.0733972, 100.00, 'ACTIVE'
)
ON DUPLICATE KEY UPDATE
    owner_user_id = @owner_id,
    name = 'Gig-Hub 합성 테스트 매장',
    representative_name = '테스트 사장',
    road_address = '서울 광진구 능동로 195-16',
    detail_address = NULL,
    phone = '0200000017',
    latitude = 37.5481384,
    longitude = 127.0733972,
    radius_meters = 100.00,
    status = 'ACTIVE',
    deleted_at = NULL;

SET @workplace_id = (
    SELECT id
    FROM workplaces
    WHERE business_registration_number = '0000000017'
);

-- 사업장 고정 QR(#381). 상세한 근거는 test-invitation-accept.sql 의 같은 블록에 있다.
-- 두 Fixture 는 같은 Database 에 공존하므로 nonce 도 식별자 대역(17 / 267)으로 나눈다.
INSERT INTO qr_tokens (
    workplace_id, issued_by_user_id, token_nonce, status
) VALUES (
    @workplace_id, @owner_id, UNHEX('00000000000000000000000000000017'), 'ACTIVE'
)
ON DUPLICATE KEY UPDATE
    workplace_id = @workplace_id,
    issued_by_user_id = @owner_id,
    token_nonce = UNHEX('00000000000000000000000000000017'),
    status = 'ACTIVE',
    revoked_at = NULL;

INSERT INTO wallets (user_id, currency, available_balance, locked_balance)
VALUES (@owner_id, 'KRW', 700000, 300000)
ON DUPLICATE KEY UPDATE
    available_balance = 700000,
    locked_balance = 300000;

INSERT INTO wallets (user_id, currency, available_balance, locked_balance)
VALUES (@worker_id, 'KRW', 0, 0)
ON DUPLICATE KEY UPDATE
    available_balance = 0,
    locked_balance = 0;

SET @owner_wallet_id = (
    SELECT id FROM wallets WHERE user_id = @owner_id AND currency = 'KRW'
);
SET @worker_wallet_id = (
    SELECT id FROM wallets WHERE user_id = @worker_id AND currency = 'KRW'
);

INSERT INTO mock_bank_accounts (
    bank_code, mock_account_number, pin, mock_fintech_use_num,
    currency, balance, available_amount, status
) VALUES (
    '004', '170000000001', '0000', 'TEST-FINTECH-OWNER-17',
    'KRW', 1000000, 1000000, 'ACTIVE'
)
ON DUPLICATE KEY UPDATE
    pin = '0000',
    balance = 1000000,
    available_amount = 1000000,
    status = 'ACTIVE';

INSERT INTO mock_bank_accounts (
    bank_code, mock_account_number, pin, mock_fintech_use_num,
    currency, balance, available_amount, status
) VALUES (
    '004', '170000000002', '0000', 'TEST-FINTECH-WORKER-17',
    'KRW', 0, 0, 'ACTIVE'
)
ON DUPLICATE KEY UPDATE
    pin = '0000',
    balance = 0,
    available_amount = 0,
    status = 'ACTIVE';

-- 기존 004 계좌와 함께 canonical bank code 20종을 모두 검증할 수 있도록 합성 계좌를 유지한다.
INSERT INTO mock_bank_accounts (
    bank_code, mock_account_number, pin, mock_fintech_use_num,
    currency, balance, available_amount, status
) VALUES
    (
        '088', '110245000088', '0000', 'TEST-FINTECH-SHINHAN-245',
        'KRW', 1000000, 1000000, 'ACTIVE'
    ),
    (
        '020', '1002245000020', '0000', 'TEST-FINTECH-WOORI-245',
        'KRW', 1000000, 1000000, 'ACTIVE'
    ),
    (
        '081', '24591000000081', '0000', 'TEST-FINTECH-HANA-245',
        'KRW', 1000000, 1000000, 'ACTIVE'
    ),
    (
        '011', '3010245000011', '0000', 'TEST-FINTECH-NH-245',
        'KRW', 1000000, 1000000, 'ACTIVE'
    ),
    (
        '003', '00324500000003', '0000', 'TEST-FINTECH-IBK-245',
        'KRW', 1000000, 1000000, 'ACTIVE'
    ),
    (
        '090', '3333245000090', '0000', 'TEST-FINTECH-KAKAO-245',
        'KRW', 1000000, 1000000, 'ACTIVE'
    ),
    (
        '092', '100024500092', '0000', 'TEST-FINTECH-TOSS-245',
        'KRW', 1000000, 1000000, 'ACTIVE'
    ),
    (
        '089', '100245000089', '0000', 'TEST-FINTECH-KBANK-245',
        'KRW', 1000000, 1000000, 'ACTIVE'
    ),
    (
        '032', '1012450000032', '0000', 'TEST-FINTECH-BUSAN-245',
        'KRW', 1000000, 1000000, 'ACTIVE'
    ),
    (
        '031', '508245000031', '0000', 'TEST-FINTECH-DGB-245',
        'KRW', 1000000, 1000000, 'ACTIVE'
    ),
    (
        '131', '508245000131', '0000', 'TEST-FINTECH-IM-245',
        'KRW', 1000000, 1000000, 'ACTIVE'
    ),
    (
        '034', '110245000034', '0000', 'TEST-FINTECH-GWANGJU-245',
        'KRW', 1000000, 1000000, 'ACTIVE'
    ),
    (
        '023', '02324500001', '0000', 'TEST-FINTECH-SC-245',
        'KRW', 1000000, 1000000, 'ACTIVE'
    ),
    (
        '027', '0272450001', '0000', 'TEST-FINTECH-CITI-245',
        'KRW', 1000000, 1000000, 'ACTIVE'
    ),
    (
        '002', '00224500000002', '0000', 'TEST-FINTECH-KDB-245',
        'KRW', 1000000, 1000000, 'ACTIVE'
    ),
    (
        '007', '101245000007', '0000', 'TEST-FINTECH-SUHYUP-245',
        'KRW', 1000000, 1000000, 'ACTIVE'
    ),
    (
        '045', '9002245000045', '0000', 'TEST-FINTECH-MG-245',
        'KRW', 1000000, 1000000, 'ACTIVE'
    ),
    (
        '048', '0482450000048', '0000', 'TEST-FINTECH-CREDIT-UNION-245',
        'KRW', 1000000, 1000000, 'ACTIVE'
    ),
    (
        '071', '07124500000071', '0000', 'TEST-FINTECH-POST-245',
        'KRW', 1000000, 1000000, 'ACTIVE'
    )
ON DUPLICATE KEY UPDATE
    pin = '0000',
    balance = 1000000,
    available_amount = 1000000,
    status = 'ACTIVE';

SET @owner_account_id = (
    SELECT id
    FROM mock_bank_accounts
    WHERE mock_fintech_use_num = 'TEST-FINTECH-OWNER-17'
);
SET @worker_account_id = (
    SELECT id
    FROM mock_bank_accounts
    WHERE mock_fintech_use_num = 'TEST-FINTECH-WORKER-17'
);

SET @work_case_id = (
    SELECT id
    FROM work_cases
    WHERE employer_id = @owner_id
      AND workplace_id = @workplace_id
      AND title = @seed_title
    ORDER BY id
    LIMIT 1
);

INSERT INTO work_cases (
    employer_id, worker_id, workplace_id, title,
    starts_at, ends_at, break_minutes, break_paid,
    workplace_name, workplace_address,
    workplace_latitude, workplace_longitude, allowed_radius_meters,
    agreed_wage, terms_version, status
)
SELECT
    @owner_id, @worker_id, @workplace_id, @seed_title,
    '2026-08-01 09:00:00.000000', '2026-08-01 18:00:00.000000', 60, 0,
    'Gig-Hub 합성 테스트 매장', '서울 광진구 능동로 195-16',
    37.5481384, 127.0733972, 100.00,
    300000, 1, 'ACCEPTED'
WHERE @work_case_id IS NULL;

SET @work_case_id = COALESCE(@work_case_id, LAST_INSERT_ID());

DELETE FROM document_access_logs
WHERE document_id IN (
    SELECT id FROM documents WHERE work_case_id = @work_case_id
);
DELETE FROM document_signatures
WHERE document_id IN (
    SELECT id FROM documents WHERE work_case_id = @work_case_id
);
DELETE FROM document_shares
WHERE document_id IN (
    SELECT id FROM documents WHERE work_case_id = @work_case_id
);
DELETE FROM document_versions
WHERE document_id IN (
    SELECT id FROM documents WHERE work_case_id = @work_case_id
);
DELETE FROM documents WHERE work_case_id = @work_case_id;
DELETE FROM disputes WHERE work_case_id = @work_case_id;
DELETE FROM settlements WHERE work_case_id = @work_case_id;
DELETE FROM attendance_records WHERE work_case_id = @work_case_id;
-- 사업장 고정 QR은 유지하고 이전 근무 단위 QR 이력만 정리한다.
DELETE FROM qr_tokens WHERE legacy_work_case_id = @work_case_id;
DELETE FROM wallet_transactions
WHERE wallet_id IN (@owner_wallet_id, @worker_wallet_id);
DELETE FROM escrows WHERE work_case_id = @work_case_id;
DELETE FROM work_contracts WHERE work_case_id = @work_case_id;
DELETE FROM work_invitations WHERE work_case_id = @work_case_id;
-- 사용자 비귀속 합성 계좌는 다른 로컬 사용자의 충전·출금에도 쓰일 수 있다.
-- 계좌 거래를 지우기 전에 해당 거래 또는 계좌를 참조하는 자식 행을 함께 정리한다.
DELETE funding_order
FROM funding_orders AS funding_order
LEFT JOIN mock_bank_transactions AS bank_transaction
    ON bank_transaction.id = funding_order.mock_bank_transaction_id
WHERE funding_order.employer_id = @owner_id
   OR funding_order.linked_account_id IN (@owner_account_id, @worker_account_id)
   OR bank_transaction.account_id IN (@owner_account_id, @worker_account_id);
DELETE withdrawal_request
FROM withdrawal_requests AS withdrawal_request
LEFT JOIN mock_bank_transactions AS bank_transaction
    ON bank_transaction.id = withdrawal_request.mock_bank_transaction_id
WHERE withdrawal_request.user_id IN (@owner_id, @worker_id)
   OR withdrawal_request.linked_account_id IN (@owner_account_id, @worker_account_id)
   OR bank_transaction.account_id IN (@owner_account_id, @worker_account_id);
DELETE FROM mock_bank_transactions
WHERE account_id IN (@owner_account_id, @worker_account_id);

UPDATE work_cases
SET worker_id = @worker_id,
    workplace_id = @workplace_id,
    starts_at = '2026-08-01 09:00:00.000000',
    ends_at = '2026-08-01 18:00:00.000000',
    break_minutes = 60,
    break_paid = 0,
    workplace_name = 'Gig-Hub 합성 테스트 매장',
    workplace_address = '서울 광진구 능동로 195-16',
    workplace_latitude = 37.5481384,
    workplace_longitude = 127.0733972,
    allowed_radius_meters = 100.00,
    agreed_wage = 300000,
    terms_version = 1,
    status = 'ACCEPTED',
    canceled_at = NULL
WHERE id = @work_case_id;

INSERT INTO work_invitations (
    work_case_id, token_hash, status,
    expected_terms_version, expires_at,
    accepted_by_user_id, accepted_terms_version, accepted_at
) VALUES (
    @work_case_id, UNHEX(SHA2('TEST-17-CONTRACT-INVITE', 256)), 'ACCEPTED',
    1, '2026-07-31 23:59:59.000000',
    @worker_id, 1, @accepted_at
);

INSERT INTO work_contracts (
    work_case_id, employer_id, worker_id, title,
    starts_at, ends_at, break_minutes, break_paid,
    workplace_name, workplace_address,
    workplace_latitude, workplace_longitude, allowed_radius_meters,
    agreed_wage, source_terms_version, terms_snapshot, accepted_at
) VALUES (
    @work_case_id, @owner_id, @worker_id, '합성 테스트 근로계약서',
    '2026-08-01 09:00:00.000000', '2026-08-01 18:00:00.000000', 60, 0,
    'Gig-Hub 합성 테스트 매장', '서울 광진구 능동로 195-16',
    37.5481384, 127.0733972, 100.00,
    300000, 1,
    JSON_OBJECT(
        'synthetic', TRUE,
        'breakPaid', FALSE,
        'settlementGraceHours', 24,
        'templateSha256',
        '28E2AA9BA3C202F3BCB55719C097F7785C0104B5310EA5841AA16E1452B13040',
        'templateFile', 'output/pdf/gig-hub-synthetic-employment-contract.pdf'
    ),
    @accepted_at
);

INSERT INTO escrows (
    work_case_id, amount, status, held_at
) VALUES (
    @work_case_id, 300000, 'HELD', @accepted_at
);

SET @escrow_id = LAST_INSERT_ID();

INSERT INTO settlements (
    work_case_id, amount, status
) VALUES (
    @work_case_id, 300000, 'WAITING'
);

INSERT INTO wallet_transactions (
    wallet_id, work_case_id, transaction_type, amount,
    available_before, available_after, locked_before, locked_after,
    reference_type, reference_id, idempotency_key, created_at
) VALUES (
    @owner_wallet_id, NULL, 'FUNDING', 1000000,
    0, 1000000, 0, 0,
    'TEST_SEED', @owner_id, 'TEST-17-OWNER-FUNDING',
    '2026-07-22 12:59:00.000000'
), (
    @owner_wallet_id, @work_case_id, 'ESCROW_HOLD', 300000,
    1000000, 700000, 0, 300000,
    'ESCROW', @escrow_id, 'TEST-17-ESCROW-HOLD',
    @accepted_at
);

COMMIT;

SELECT
    work_case.id AS work_case_id,
    owner_user.login_id AS owner_login_id,
    worker_user.login_id AS worker_login_id,
    'Test1234!' AS test_password,
    owner_wallet.available_balance AS owner_available_balance,
    owner_wallet.locked_balance AS owner_locked_balance,
    worker_wallet.available_balance AS worker_available_balance,
    escrow.amount AS worker_secured_amount,
    escrow.status AS escrow_status,
    settlement.status AS settlement_status,
    settlement.due_at AS settlement_due_at
FROM work_cases work_case
JOIN users owner_user ON owner_user.id = work_case.employer_id
JOIN users worker_user ON worker_user.id = work_case.worker_id
JOIN wallets owner_wallet
    ON owner_wallet.user_id = owner_user.id AND owner_wallet.currency = 'KRW'
JOIN wallets worker_wallet
    ON worker_wallet.user_id = worker_user.id AND worker_wallet.currency = 'KRW'
JOIN escrows escrow ON escrow.work_case_id = work_case.id
JOIN settlements settlement ON settlement.work_case_id = work_case.id
WHERE work_case.id = @work_case_id;
