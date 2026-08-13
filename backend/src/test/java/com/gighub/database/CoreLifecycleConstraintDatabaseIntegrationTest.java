package com.gighub.database;

import com.gighub.config.RootConfig;
import com.gighub.workplace.geocoding.FixedAddressGeocoderConfig;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.mapper.WorkCaseMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("database")
class CoreLifecycleConstraintDatabaseIntegrationTest {

    private static final long FUNDING_AMOUNT = 100_000L;
    private static final long WITHDRAWAL_AMOUNT = 50_000L;
    private static final long WAGE = 120_000L;

    @Test
    void enforcesOnlyTheImplementedFinancialAndWorkLifecycleShapes() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(
                             RootConfig.class, FixedAddressGeocoderConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            WorkCaseMapper workCaseMapper = context.getBean(WorkCaseMapper.class);
            String token = UUID.randomUUID().toString().replace("-", "");
            Fixture fixture = createFixture(jdbc, token);

            try {
                verifySchemaObjects(jdbc);
                verifyFundingLifecycle(jdbc, fixture);
                verifyWithdrawalLifecycle(jdbc, fixture);
                verifyEscrowLifecycle(jdbc, fixture);
                verifyWorkCaseCancellationLifecycle(jdbc, workCaseMapper, fixture);
            } finally {
                deleteFixture(jdbc, fixture);
            }
        }
    }

    private void verifySchemaObjects(JdbcTemplate jdbc) {
        assertConstraint(jdbc, "funding_orders", "ck_funding_orders_lifecycle");
        assertConstraint(jdbc, "withdrawal_requests", "ck_withdrawal_requests_lifecycle");
        assertConstraint(jdbc, "escrows", "ck_escrows_lifecycle");
        assertConstraint(jdbc, "work_cases", "ck_work_cases_cancellation_lifecycle");
    }

    private void assertConstraint(JdbcTemplate jdbc, String table, String constraint) {
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.table_constraints"
                                + " WHERE constraint_schema = DATABASE()"
                                + " AND table_name = ?"
                                + " AND CAST(constraint_name AS BINARY) = CAST(? AS BINARY)"
                                + " AND constraint_type = 'CHECK' AND enforced = 'YES'",
                        Integer.class,
                        table,
                        constraint
                )
        );
    }

    private void verifyFundingLifecycle(JdbcTemplate jdbc, Fixture fixture) {
        String key = "IT-RF11-FUND-" + fixture.token();
        jdbc.update(
                "INSERT INTO funding_orders"
                        + " (employer_id, linked_account_id, expected_amount,"
                        + " idempotency_key, status) VALUES (?, ?, ?, ?, 'READY')",
                fixture.ownerId(),
                fixture.accountId(),
                FUNDING_AMOUNT,
                key
        );
        long orderId = idBy(jdbc, "funding_orders", "idempotency_key", key);

        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE funding_orders SET transferred_amount = ? WHERE id = ?",
                        FUNDING_AMOUNT,
                        orderId
                )
        );

        // 복구 의미가 아직 확정되지 않은 상태에는 이번 Migration이 새 필드 조합을 강제하지 않습니다.
        jdbc.update(
                "INSERT INTO funding_orders"
                        + " (employer_id, linked_account_id, expected_amount, transferred_amount,"
                        + " idempotency_key, status, failure_code, completed_at)"
                        + " VALUES (?, ?, ?, 1, ?, 'FAILED', 'RF11_TEST_FAILURE', NOW(6))",
                fixture.ownerId(),
                fixture.accountId(),
                FUNDING_AMOUNT,
                "IT-RF11-FUND-FAILED-" + fixture.token()
        );
        jdbc.update(
                "INSERT INTO funding_orders"
                        + " (employer_id, linked_account_id, expected_amount,"
                        + " idempotency_key, status)"
                        + " VALUES (?, ?, ?, ?, 'RECONCILIATION_REQUIRED')",
                fixture.ownerId(),
                fixture.accountId(),
                FUNDING_AMOUNT,
                "IT-RF11-FUND-RECON-" + fixture.token()
        );

        long bankTransactionId = insertBankTransaction(
                jdbc,
                fixture.accountId(),
                "RF11-FUND-" + fixture.token(),
                "WITHDRAW",
                FUNDING_AMOUNT,
                200_000L,
                100_000L,
                "FUNDING_ORDER",
                orderId
        );
        jdbc.update(
                "UPDATE funding_orders SET transferred_amount = ?,"
                        + " mock_bank_transaction_id = ?, status = 'COMPLETED',"
                        + " completed_at = NOW(6) WHERE id = ?",
                FUNDING_AMOUNT,
                bankTransactionId,
                orderId
        );

        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE funding_orders SET transferred_amount = NULL WHERE id = ?",
                        orderId
                )
        );
        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE funding_orders SET transferred_amount = ? WHERE id = ?",
                        FUNDING_AMOUNT - 1,
                        orderId
                )
        );

        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE funding_orders SET failure_code = 'BROKEN' WHERE id = ?",
                        orderId
                )
        );
    }

    private void verifyWithdrawalLifecycle(JdbcTemplate jdbc, Fixture fixture) {
        String key = "IT-RF11-WITHDRAW-" + fixture.token();
        jdbc.update(
                "INSERT INTO withdrawal_requests"
                        + " (user_id, wallet_id, linked_account_id, amount,"
                        + " idempotency_key, status) VALUES (?, ?, ?, ?, ?, 'READY')",
                fixture.ownerId(),
                fixture.walletId(),
                fixture.accountId(),
                WITHDRAWAL_AMOUNT,
                key
        );
        long requestId = idBy(jdbc, "withdrawal_requests", "idempotency_key", key);

        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE withdrawal_requests SET status = 'COMPLETED' WHERE id = ?",
                        requestId
                )
        );

        jdbc.update(
                "INSERT INTO withdrawal_requests"
                        + " (user_id, wallet_id, linked_account_id, amount, idempotency_key,"
                        + " status, failure_code, completed_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'PROCESSING', 'RF11_TEST_PROCESSING', NOW(6))",
                fixture.ownerId(),
                fixture.walletId(),
                fixture.accountId(),
                WITHDRAWAL_AMOUNT,
                "IT-RF11-WITHDRAW-PROCESSING-" + fixture.token()
        );
        jdbc.update(
                "INSERT INTO withdrawal_requests"
                        + " (user_id, wallet_id, linked_account_id, amount, idempotency_key, status)"
                        + " VALUES (?, ?, ?, ?, ?, 'FAILED')",
                fixture.ownerId(),
                fixture.walletId(),
                fixture.accountId(),
                WITHDRAWAL_AMOUNT,
                "IT-RF11-WITHDRAW-FAILED-" + fixture.token()
        );
        jdbc.update(
                "INSERT INTO withdrawal_requests"
                        + " (user_id, wallet_id, linked_account_id, amount, idempotency_key, status)"
                        + " VALUES (?, ?, ?, ?, ?, 'RECONCILIATION_REQUIRED')",
                fixture.ownerId(),
                fixture.walletId(),
                fixture.accountId(),
                WITHDRAWAL_AMOUNT,
                "IT-RF11-WITHDRAW-RECON-" + fixture.token()
        );

        long bankTransactionId = insertBankTransaction(
                jdbc,
                fixture.accountId(),
                "RF11-WITHDRAW-" + fixture.token(),
                "DEPOSIT",
                WITHDRAWAL_AMOUNT,
                100_000L,
                150_000L,
                "WITHDRAWAL_REQUEST",
                requestId
        );
        jdbc.update(
                "UPDATE withdrawal_requests SET mock_bank_transaction_id = ?,"
                        + " status = 'COMPLETED', completed_at = NOW(6) WHERE id = ?",
                bankTransactionId,
                requestId
        );

        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE withdrawal_requests SET completed_at = NULL WHERE id = ?",
                        requestId
                )
        );
    }

    private void verifyEscrowLifecycle(JdbcTemplate jdbc, Fixture fixture) {
        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "INSERT INTO escrows (work_case_id, amount, status)"
                                + " VALUES (?, ?, 'HELD')",
                        fixture.releaseWorkCaseId(),
                        WAGE
                )
        );

        jdbc.update(
                "INSERT INTO escrows (work_case_id, amount, status, held_at)"
                        + " VALUES (?, ?, 'HELD', '2030-01-01 10:00:00.000000')",
                fixture.releaseWorkCaseId(),
                WAGE
        );
        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE escrows SET status = 'RELEASED' WHERE work_case_id = ?",
                        fixture.releaseWorkCaseId()
                )
        );
        jdbc.update(
                "UPDATE escrows SET status = 'RELEASED',"
                        + " released_at = '2030-01-01 10:00:00.000000'"
                        + " WHERE work_case_id = ?",
                fixture.releaseWorkCaseId()
        );

        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE escrows SET released_at = '2029-12-31 23:59:59.999999'"
                                + " WHERE work_case_id = ?",
                        fixture.releaseWorkCaseId()
                )
        );

        jdbc.update(
                "INSERT INTO escrows (work_case_id, amount, status, held_at)"
                        + " VALUES (?, ?, 'HELD', NOW(6))",
                fixture.refundWorkCaseId(),
                WAGE
        );
        jdbc.update(
                "UPDATE escrows SET status = 'REFUNDED', refunded_at = NOW(6)"
                        + " WHERE work_case_id = ?",
                fixture.refundWorkCaseId()
        );
        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE escrows SET released_at = NOW(6) WHERE work_case_id = ?",
                        fixture.refundWorkCaseId()
                )
        );

        jdbc.update(
                "INSERT INTO escrows (work_case_id, amount, status)"
                        + " VALUES (?, ?, 'UNFUNDED')",
                fixture.unfundedWorkCaseId(),
                WAGE
        );

        // ON_HOLD의 timestamp 의미는 아직 제품 계약이 없으므로 기존 허용 범위를 그대로 둡니다.
        jdbc.update(
                "INSERT INTO escrows"
                        + " (work_case_id, amount, status, released_at, on_hold_at)"
                        + " VALUES (?, ?, 'ON_HOLD', NOW(6), NULL)",
                fixture.cancelWorkCaseId(),
                WAGE
        );
    }

    private void verifyWorkCaseCancellationLifecycle(
            JdbcTemplate jdbc,
            WorkCaseMapper workCaseMapper,
            Fixture fixture) {
        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE work_cases SET status = 'CANCELED' WHERE id = ?",
                        fixture.cancelWorkCaseId()
                )
        );
        assertEquals(
                1,
                workCaseMapper.updateWorkStatus(
                        fixture.cancelWorkCaseId(),
                        List.of(WorkCaseStatus.DRAFT),
                        WorkCaseStatus.CANCELED
                )
        );
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM work_cases"
                                + " WHERE id = ? AND status = 'CANCELED'"
                                + " AND canceled_at IS NOT NULL",
                        Integer.class,
                        fixture.cancelWorkCaseId()
                )
        );

        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE work_cases SET canceled_at = NOW(6) WHERE id = ?",
                        fixture.releaseWorkCaseId()
                )
        );
    }

    private Fixture createFixture(JdbcTemplate jdbc, String token) {
        String ownerLogin = "it_rf11_owner_" + token;
        String workerLogin = "it_rf11_worker_" + token;
        insertUser(jdbc, ownerLogin, "OWNER");
        insertUser(jdbc, workerLogin, "WORKER");
        long ownerId = idBy(jdbc, "users", "login_id", ownerLogin);
        long workerId = idBy(jdbc, "users", "login_id", workerLogin);

        String businessNumber = String.format(
                "%010d",
                Integer.toUnsignedLong(token.hashCode())
        );
        jdbc.update(
                "INSERT INTO workplaces"
                        + " (owner_user_id, business_registration_number, name,"
                        + " representative_name, road_address, phone, status)"
                        + " VALUES (?, ?, 'RF11 제약 사업장', '테스트 대표',"
                        + " '서울특별시 테스트로 11', '02-1111-1111', 'ACTIVE')",
                ownerId,
                businessNumber
        );
        long workplaceId = idBy(
                jdbc,
                "workplaces",
                "business_registration_number",
                businessNumber
        );

        jdbc.update(
                "INSERT INTO wallets (user_id, currency) VALUES (?, 'KRW')",
                ownerId
        );
        long walletId = jdbc.queryForObject(
                "SELECT id FROM wallets WHERE user_id = ? AND currency = 'KRW'",
                Long.class,
                ownerId
        );

        String accountNumber = "92" + token.substring(0, 18);
        jdbc.update(
                "INSERT INTO mock_bank_accounts"
                        + " (bank_code, mock_account_number, pin, mock_fintech_use_num,"
                        + " currency, balance, available_amount, status)"
                        + " VALUES ('004', ?, '0000', ?, 'KRW', 200000, 200000, 'ACTIVE')",
                accountNumber,
                "RF11-FT-" + token
        );
        long accountId = idBy(
                jdbc,
                "mock_bank_accounts",
                "mock_account_number",
                accountNumber
        );

        long cancelWorkCaseId = insertWorkCase(
                jdbc, ownerId, workerId, workplaceId, "RF11 취소 " + token);
        long releaseWorkCaseId = insertWorkCase(
                jdbc, ownerId, workerId, workplaceId, "RF11 해제 " + token);
        long refundWorkCaseId = insertWorkCase(
                jdbc, ownerId, workerId, workplaceId, "RF11 환불 " + token);
        long unfundedWorkCaseId = insertWorkCase(
                jdbc, ownerId, workerId, workplaceId, "RF11 미예치 " + token);

        return new Fixture(
                token,
                ownerId,
                workerId,
                workplaceId,
                walletId,
                accountId,
                cancelWorkCaseId,
                releaseWorkCaseId,
                refundWorkCaseId,
                unfundedWorkCaseId,
                ownerLogin,
                workerLogin
        );
    }

    private void insertUser(JdbcTemplate jdbc, String loginId, String role) {
        jdbc.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'schema-test-hash', 'RF11 제약 테스트', ?, 'ACTIVE')",
                loginId,
                loginId + "@example.test",
                role
        );
    }

    private long insertWorkCase(
            JdbcTemplate jdbc,
            long ownerId,
            long workerId,
            long workplaceId,
            String title) {
        jdbc.update(
                "INSERT INTO work_cases"
                        + " (employer_id, worker_id, workplace_id, title, starts_at, ends_at,"
                        + " break_minutes, break_paid, workplace_name, workplace_address,"
                        + " allowed_radius_meters, agreed_wage, terms_version, status)"
                        + " VALUES (?, ?, ?, ?, '2030-01-01 09:00:00',"
                        + " '2030-01-01 18:00:00', 60, 0, 'RF11 제약 사업장',"
                        + " '서울특별시 테스트로 11', 100, ?, 1, 'DRAFT')",
                ownerId,
                workerId,
                workplaceId,
                title,
                WAGE
        );
        return idBy(jdbc, "work_cases", "title", title);
    }

    private long insertBankTransaction(
            JdbcTemplate jdbc,
            long accountId,
            String bankTranId,
            String transferType,
            long amount,
            long before,
            long after,
            String referenceType,
            long referenceId) {
        jdbc.update(
                "INSERT INTO mock_bank_transactions"
                        + " (account_id, bank_tran_id, transfer_type, amount,"
                        + " balance_before, balance_after, reference_type, reference_id, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SUCCESS')",
                accountId,
                bankTranId,
                transferType,
                amount,
                before,
                after,
                referenceType,
                referenceId
        );
        return idBy(jdbc, "mock_bank_transactions", "bank_tran_id", bankTranId);
    }

    private void deleteFixture(JdbcTemplate jdbc, Fixture fixture) {
        jdbc.update(
                "DELETE FROM funding_orders WHERE employer_id = ?",
                fixture.ownerId()
        );
        jdbc.update(
                "DELETE FROM withdrawal_requests WHERE user_id = ?",
                fixture.ownerId()
        );
        jdbc.update(
                "DELETE FROM mock_bank_transactions WHERE account_id = ?",
                fixture.accountId()
        );
        jdbc.update(
                "DELETE FROM escrows WHERE work_case_id IN (?, ?, ?, ?)",
                fixture.cancelWorkCaseId(),
                fixture.releaseWorkCaseId(),
                fixture.refundWorkCaseId(),
                fixture.unfundedWorkCaseId()
        );
        jdbc.update(
                "DELETE FROM work_cases WHERE id IN (?, ?, ?, ?)",
                fixture.cancelWorkCaseId(),
                fixture.releaseWorkCaseId(),
                fixture.refundWorkCaseId(),
                fixture.unfundedWorkCaseId()
        );
        jdbc.update("DELETE FROM wallets WHERE id = ?", fixture.walletId());
        jdbc.update("DELETE FROM workplaces WHERE id = ?", fixture.workplaceId());
        jdbc.update("DELETE FROM mock_bank_accounts WHERE id = ?", fixture.accountId());
        jdbc.update(
                "DELETE FROM users WHERE id IN (?, ?)",
                fixture.ownerId(),
                fixture.workerId()
        );
    }

    private long idBy(JdbcTemplate jdbc, String table, String column, Object value) {
        return jdbc.queryForObject(
                "SELECT id FROM " + table + " WHERE " + column + " = ?",
                Long.class,
                value
        );
    }

    private record Fixture(
            String token,
            long ownerId,
            long workerId,
            long workplaceId,
            long walletId,
            long accountId,
            long cancelWorkCaseId,
            long releaseWorkCaseId,
            long refundWorkCaseId,
            long unfundedWorkCaseId,
            String ownerLogin,
            String workerLogin) {
    }
}
