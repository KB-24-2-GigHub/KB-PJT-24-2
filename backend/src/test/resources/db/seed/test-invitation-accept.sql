-- 초대 수락 E2E(#267)의 사전 데이터만 만듭니다.
--
-- 근무·초대·계약·에스크로는 넣지 않습니다. 그 넷은 실제 OWNER API 흐름으로 만들어야
-- 초대 Token 원문이 발급 응답에서만 나오고 저장소에는 Hash만 남습니다.
--
-- 완료형 test-contract-escrow.sql과 같은 DB에 공존해야 하므로 식별자를 267 대역으로
-- 분리하고, 정리 범위를 이 Fixture가 만든 사업장의 근무로만 한정합니다.

SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SET time_zone = '+09:00';

START TRANSACTION;

-- test-contract-escrow.sql과 같은 'Test1234!' bcrypt Hash입니다.
SET @password_hash = '$2a$12$B.pEu4sY.xoGiLOP1m5dPuuc1xctCXGm1Cwe85k/.yBMefiZigpqq';
SET @business_registration_number = '0000000267';
SET @owner_available_balance = 1000000;

INSERT INTO users (
    login_id, email, password_hash, name, phone, role, status
) VALUES (
    'test_owner_267', 'test-owner-267@example.invalid', @password_hash,
    '초대 테스트 사장', '01000000267', 'OWNER', 'ACTIVE'
)
ON DUPLICATE KEY UPDATE
    password_hash = @password_hash,
    name = '초대 테스트 사장',
    phone = '01000000267',
    role = 'OWNER',
    status = 'ACTIVE',
    deleted_at = NULL;

INSERT INTO users (
    login_id, email, password_hash, name, phone, role, status
) VALUES (
    'test_worker_267', 'test-worker-267@example.invalid', @password_hash,
    '초대 테스트 근로자', '01000001267', 'WORKER', 'ACTIVE'
)
ON DUPLICATE KEY UPDATE
    password_hash = @password_hash,
    name = '초대 테스트 근로자',
    phone = '01000001267',
    role = 'WORKER',
    status = 'ACTIVE',
    deleted_at = NULL;

SET @owner_id = (SELECT id FROM users WHERE login_id = 'test_owner_267');
SET @worker_id = (SELECT id FROM users WHERE login_id = 'test_worker_267');

INSERT INTO workplaces (
    owner_user_id, business_registration_number, name,
    representative_name, road_address, detail_address, phone,
    latitude, longitude, radius_meters, status
) VALUES (
    @owner_id, @business_registration_number, 'Gig-Hub 초대 수락 E2E 매장',
    '초대 테스트 사장', '서울 광진구 능동로 195-16', NULL, '0200000267',
    37.5481384, 127.0733972, 100.00, 'ACTIVE'
)
ON DUPLICATE KEY UPDATE
    owner_user_id = @owner_id,
    name = 'Gig-Hub 초대 수락 E2E 매장',
    representative_name = '초대 테스트 사장',
    road_address = '서울 광진구 능동로 195-16',
    detail_address = NULL,
    phone = '0200000267',
    latitude = 37.5481384,
    longitude = 127.0733972,
    radius_meters = 100.00,
    status = 'ACTIVE',
    deleted_at = NULL;

SET @workplace_id = (
    SELECT id
    FROM workplaces
    WHERE business_registration_number = @business_registration_number
);

-- 사업장 고정 QR(#381).
--
-- 앱으로 사업장을 등록하면 WorkplaceServiceImpl 이 같은 트랜잭션에서 QR 을 함께 발급한다.
-- 시드는 workplaces 를 직접 INSERT 하므로 그 경로를 타지 않아, 깨끗한 Database 에서는
-- OWNER QR 화면이 WORKPLACE_QR_INTEGRITY 로 막히고 WORKER 스캔도 시작할 수 없었다.
-- 조회가 QR 을 만들지 않는 것은 발급 누락을 드러내려는 의도된 동작이므로(WorkplaceQrServiceImpl),
-- 앱을 고치는 대신 시드가 앱과 같은 결과를 만든다.
--
-- token_nonce 는 공개 식별자이며 QR 서명 비밀이 아니다. Token 문자열은 저장하지 않고
-- 환경의 HMAC Key 로 (keyId, workplaceId, nonce) 를 매번 다시 서명해 만들므로, 고정 nonce 를
-- 커밋해도 다른 환경의 QR 을 위조할 수 없다.
--
-- 두 유일 제약(uk_qr_tokens_workplace_active, uk_qr_tokens_token_nonce)이 반복 적용 시 같은
-- 행으로 모이도록 nonce 를 고정한다. 그래서 시드를 몇 번 돌려도 사업장당 ACTIVE 는 한 건이다.
INSERT INTO qr_tokens (
    workplace_id, issued_by_user_id, token_nonce, status
) VALUES (
    @workplace_id, @owner_id, UNHEX('00000000000000000000000000000267'), 'ACTIVE'
)
ON DUPLICATE KEY UPDATE
    workplace_id = @workplace_id,
    issued_by_user_id = @owner_id,
    token_nonce = UNHEX('00000000000000000000000000000267'),
    status = 'ACTIVE',
    revoked_at = NULL;

INSERT INTO wallets (user_id, currency, available_balance, locked_balance)
VALUES (@owner_id, 'KRW', @owner_available_balance, 0)
ON DUPLICATE KEY UPDATE
    available_balance = @owner_available_balance,
    locked_balance = 0;

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

-- 이전 실행에서 API가 만든 근무와 그 하위 기록만 되돌립니다. 모든 조건이 이 Fixture의
-- 사장님과 사업장에 묶여 있으므로 다른 개발 데이터와 기존 Seed는 건드리지 않습니다.
-- 자식 행을 먼저 지워야 하므로 FK 참조의 역순으로 내려갑니다.
DELETE FROM document_access_logs
WHERE document_id IN (
    SELECT id FROM documents
    WHERE work_case_id IN (
        SELECT id FROM work_cases
        WHERE employer_id = @owner_id AND workplace_id = @workplace_id
    )
);
DELETE FROM document_signatures
WHERE document_id IN (
    SELECT id FROM documents
    WHERE work_case_id IN (
        SELECT id FROM work_cases
        WHERE employer_id = @owner_id AND workplace_id = @workplace_id
    )
);
DELETE FROM document_shares
WHERE document_id IN (
    SELECT id FROM documents
    WHERE work_case_id IN (
        SELECT id FROM work_cases
        WHERE employer_id = @owner_id AND workplace_id = @workplace_id
    )
);
DELETE FROM document_versions
WHERE document_id IN (
    SELECT id FROM documents
    WHERE work_case_id IN (
        SELECT id FROM work_cases
        WHERE employer_id = @owner_id AND workplace_id = @workplace_id
    )
);
DELETE FROM documents
WHERE work_case_id IN (
    SELECT id FROM work_cases
    WHERE employer_id = @owner_id AND workplace_id = @workplace_id
);
DELETE FROM disputes
WHERE work_case_id IN (
    SELECT id FROM work_cases
    WHERE employer_id = @owner_id AND workplace_id = @workplace_id
);
DELETE FROM settlements
WHERE work_case_id IN (
    SELECT id FROM work_cases
    WHERE employer_id = @owner_id AND workplace_id = @workplace_id
);
DELETE FROM attendance_records
WHERE work_case_id IN (
    SELECT id FROM work_cases
    WHERE employer_id = @owner_id AND workplace_id = @workplace_id
);
-- 사업장 고정 QR은 유지하고 이전 근무 단위 QR 이력만 정리합니다.
DELETE FROM qr_tokens
WHERE legacy_work_case_id IN (
    SELECT id FROM work_cases
    WHERE employer_id = @owner_id AND workplace_id = @workplace_id
);
-- 개시 FUNDING 한 줄까지 지우고 아래에서 다시 넣어야 원장이 잔액과 맞습니다.
DELETE FROM wallet_transactions
WHERE wallet_id IN (@owner_wallet_id, @worker_wallet_id);
DELETE FROM escrows
WHERE work_case_id IN (
    SELECT id FROM work_cases
    WHERE employer_id = @owner_id AND workplace_id = @workplace_id
);
DELETE FROM work_contracts
WHERE work_case_id IN (
    SELECT id FROM work_cases
    WHERE employer_id = @owner_id AND workplace_id = @workplace_id
);
DELETE FROM work_invitations
WHERE work_case_id IN (
    SELECT id FROM work_cases
    WHERE employer_id = @owner_id AND workplace_id = @workplace_id
);
-- 남아 있는 수락 Claim은 다음 실행의 최초 수락을 Replay로 만들어 버립니다.
DELETE FROM idempotency_requests WHERE user_id IN (@owner_id, @worker_id);
DELETE FROM work_cases
WHERE employer_id = @owner_id AND workplace_id = @workplace_id;

-- 사장님 가용 잔액의 출처를 원장에도 남겨 수락 후 대사가 성립하게 합니다.
INSERT INTO wallet_transactions (
    wallet_id, work_case_id, transaction_type, amount,
    available_before, available_after, locked_before, locked_after,
    reference_type, reference_id, idempotency_key
) VALUES (
    @owner_wallet_id, NULL, 'FUNDING', @owner_available_balance,
    0, @owner_available_balance, 0, 0,
    'TEST_SEED', @owner_id, 'TEST-267-OWNER-FUNDING'
);

COMMIT;

SELECT
    owner_user.login_id AS owner_login_id,
    worker_user.login_id AS worker_login_id,
    'Test1234!' AS test_password,
    workplace.id AS workplace_id,
    owner_wallet.available_balance AS owner_available_balance,
    owner_wallet.locked_balance AS owner_locked_balance,
    worker_wallet.available_balance AS worker_available_balance,
    (
        SELECT COUNT(*)
        FROM work_cases
        WHERE employer_id = owner_user.id AND workplace_id = workplace.id
    ) AS remaining_work_cases
FROM users owner_user
JOIN users worker_user ON worker_user.login_id = 'test_worker_267'
JOIN workplaces workplace
    ON workplace.business_registration_number = @business_registration_number
JOIN wallets owner_wallet
    ON owner_wallet.user_id = owner_user.id AND owner_wallet.currency = 'KRW'
JOIN wallets worker_wallet
    ON worker_wallet.user_id = worker_user.id AND worker_wallet.currency = 'KRW'
WHERE owner_user.login_id = 'test_owner_267';
