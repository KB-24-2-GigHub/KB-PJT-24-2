-- 통합 시연 SEED 전용 전체 초기화 조각입니다.
-- Flyway 이력과 Schema는 보존하고 애플리케이션 데이터만 FK 역순으로 삭제합니다.
-- 이 파일은 단독 실행하지 않고 demo-*.sql이 같은 mysql 세션에서 SOURCE 합니다.

SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SET time_zone = '+09:00';

START TRANSACTION;

SET @seed_now = CURRENT_TIMESTAMP(6);
SET @seed_today = CURRENT_DATE();

DELETE FROM notifications;
DELETE FROM dispute_ai_reviews;
DELETE FROM document_access_logs;
DELETE FROM document_signatures;
DELETE FROM document_shares;
DELETE FROM document_versions;
DELETE FROM documents;
DELETE FROM idempotency_requests;
DELETE FROM disputes;
DELETE FROM settlements;
DELETE FROM attendance_records;
DELETE FROM wallet_transactions;
DELETE FROM escrows;
DELETE FROM work_contracts;
DELETE FROM work_invitations;
DELETE FROM qr_tokens;
DELETE FROM funding_orders;
DELETE FROM withdrawal_requests;
DELETE FROM mock_bank_transactions;
DELETE FROM user_badges;
DELETE FROM password_reset_tokens;
DELETE FROM work_cases;
DELETE FROM wallets;
DELETE FROM workplaces;
DELETE FROM mock_bank_accounts;
DELETE FROM users;
