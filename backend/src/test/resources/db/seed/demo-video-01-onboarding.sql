-- 영상 체크포인트 01: 첫 로그인부터 사업장 등록·충전·출금까지 직접 시연합니다.
-- OWNER는 사업장 0개, Wallet 0원이며 Mock 은행 계좌에만 충전 재원이 있습니다.

SOURCE /seed/demo-reset.inc

SET @owner_available = 0;
SET @owner_locked = 0;
SET @owner_bank_balance = 5000000;
SET @worker_a_available = 0;
SET @worker_b_available = 0;
SET @worker_c_available = 0;

SOURCE /seed/demo-users.inc

COMMIT;

SELECT
    'video-01-onboarding' AS scenario_key,
    @seed_now AS seed_now,
    'gigsajang' AS owner_login_id,
    'Demo1234!' AS demo_password,
    '004' AS bank_code,
    '110000001001' AS owner_account_number,
    '0000' AS bank_pin,
    (SELECT COUNT(*) FROM workplaces) AS workplace_count,
    (SELECT available_balance FROM wallets WHERE id = @owner_wallet_id) AS owner_available_balance,
    (SELECT locked_balance FROM wallets WHERE id = @owner_wallet_id) AS owner_locked_balance;
