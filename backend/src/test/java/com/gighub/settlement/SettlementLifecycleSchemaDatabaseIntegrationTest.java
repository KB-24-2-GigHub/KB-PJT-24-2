package com.gighub.settlement;

import com.gighub.config.RootConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("database")
class SettlementLifecycleSchemaDatabaseIntegrationTest {

    private static final long WAGE = 120_000L;

    @Test
    void enforcesSettlementLifecycleRetryAuditAndDisputeTitle() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            String token = UUID.randomUUID().toString().replace("-", "");
            Fixture fixture = createFixture(jdbc, token);

            try {
                verifySchemaObjects(jdbc);
                verifyScheduledPayoutLifecycle(jdbc, fixture.scheduledSettlementId());
                verifyNoShowRefundLifecycle(
                        jdbc,
                        fixture.refundSettlementId(),
                        fixture.ownerId()
                );
                verifyFailedLifecycle(jdbc, fixture.failedSettlementId());
                verifyDisputeTitle(jdbc, fixture.workCaseId(), fixture.ownerId());
            } finally {
                deleteFixture(jdbc, fixture);
            }
        }
    }

    private void verifySchemaObjects(JdbcTemplate jdbc) {
        assertEquals(
                3,
                count(
                        jdbc,
                        "SELECT COUNT(*) FROM information_schema.columns"
                                + " WHERE table_schema = DATABASE()"
                                + " AND table_name = 'settlements'"
                                + " AND column_name IN"
                                + " ('retry_count', 'last_failure_at', 'next_retry_at')"
                )
        );
        assertEquals(
                1,
                count(
                        jdbc,
                        "SELECT COUNT(*) FROM information_schema.statistics"
                                + " WHERE table_schema = DATABASE()"
                                + " AND table_name = 'settlements'"
                                + " AND index_name = 'idx_settlements_status_due_at'"
                                + " AND seq_in_index = 1 AND column_name = 'status'"
                )
        );
        assertEquals(
                1,
                count(
                        jdbc,
                        "SELECT COUNT(*) FROM information_schema.statistics"
                                + " WHERE table_schema = DATABASE()"
                                + " AND table_name = 'settlements'"
                                + " AND index_name = 'idx_settlements_status_due_at'"
                                + " AND seq_in_index = 2 AND column_name = 'due_at'"
                )
        );
    }

    private void verifyScheduledPayoutLifecycle(JdbcTemplate jdbc, long settlementId) {
        // WAITING에는 지급 예정 시각이나 재시도 흔적을 미리 넣을 수 없다.
        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE settlements SET due_at = NOW(6) WHERE id = ?",
                        settlementId
                )
        );
        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE settlements SET retry_count = 1,"
                                + " failure_code = 'INVALID_WAITING_RETRY',"
                                + " last_failure_at = NOW(6), next_retry_at = NOW(6)"
                                + " WHERE id = ?",
                        settlementId
                )
        );

        jdbc.update(
                "UPDATE settlements SET status = 'SCHEDULED',"
                        + " due_at = DATE_ADD(NOW(6), INTERVAL 1 HOUR),"
                        + " worker_paid_amount = amount, owner_refund_amount = 0,"
                        + " deduction_base_minutes = 480, late_minutes = 0,"
                        + " early_leave_minutes = 0,"
                        + " calculation_reason = 'CHECKED_OUT',"
                        + " calculation_version = 'ATTENDANCE_V1',"
                        + " calculated_at = NOW(6) WHERE id = ?",
                settlementId
        );
        jdbc.update(
                "UPDATE settlements SET retry_count = 1,"
                        + " failure_code = 'LOCK_TIMEOUT', last_failure_at = NOW(6),"
                        + " next_retry_at = DATE_ADD(NOW(6), INTERVAL 1 MINUTE)"
                        + " WHERE id = ?",
                settlementId
        );

        // 분쟁 보류는 지급 예정 시각과 재시도 감사 흔적을 그대로 보존한다.
        jdbc.update(
                "UPDATE settlements SET status = 'ON_HOLD' WHERE id = ?",
                settlementId
        );
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM settlements WHERE id = ?"
                                + " AND status = 'ON_HOLD' AND due_at IS NOT NULL"
                                + " AND retry_count = 1 AND failure_code = 'LOCK_TIMEOUT'"
                                + " AND last_failure_at IS NOT NULL"
                                + " AND next_retry_at IS NOT NULL",
                        Integer.class,
                        settlementId
                )
        );
        jdbc.update(
                "UPDATE settlements SET status = 'SCHEDULED', retry_count = 4"
                        + " WHERE id = ?",
                settlementId
        );

        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE settlements SET retry_count = 5 WHERE id = ?",
                        settlementId
                )
        );

        // Scheduler 선점은 승인자가 없을 수 있고, 직전 실패 이력은 감사용으로 남긴다.
        jdbc.update(
                "UPDATE settlements SET status = 'PROCESSING',"
                        + " processing_at = NOW(6), failure_code = NULL,"
                        + " next_retry_at = NULL WHERE id = ?",
                settlementId
        );

        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE settlements SET next_retry_at = NOW(6) WHERE id = ?",
                        settlementId
                )
        );
        jdbc.update(
                "UPDATE settlements SET status = 'COMPLETED', completed_at = NOW(6)"
                        + " WHERE id = ?",
                settlementId
        );

        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE settlements SET next_retry_at = NOW(6) WHERE id = ?",
                        settlementId
                )
        );
        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE settlements SET processing_at = NULL WHERE id = ?",
                        settlementId
                )
        );

        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE settlements SET due_at = NULL WHERE id = ?",
                        settlementId
                )
        );
    }

    private void verifyNoShowRefundLifecycle(
            JdbcTemplate jdbc,
            long settlementId,
            long ownerId) {
        jdbc.update(
                "UPDATE settlements SET status = 'PROCESSING',"
                        + " approved_by_user_id = ?, processing_at = NOW(6),"
                        + " worker_paid_amount = 0, owner_refund_amount = amount,"
                        + " deduction_base_minutes = 480, late_minutes = 0,"
                        + " early_leave_minutes = 0,"
                        + " calculation_reason = 'NO_SHOW',"
                        + " calculation_version = 'ATTENDANCE_V1',"
                        + " calculated_at = NOW(6)"
                        + " WHERE id = ?",
                ownerId,
                settlementId
        );
        jdbc.update(
                "UPDATE settlements SET status = 'REFUNDED', completed_at = NOW(6)"
                        + " WHERE id = ?",
                settlementId
        );

        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE settlements SET retry_count = 1,"
                                + " last_failure_at = NOW(6) WHERE id = ?",
                        settlementId
                )
        );
        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE settlements SET processing_at = NULL WHERE id = ?",
                        settlementId
                )
        );
        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE settlements SET due_at = NOW(6) WHERE id = ?",
                        settlementId
                )
        );
    }

    private void verifyFailedLifecycle(JdbcTemplate jdbc, long settlementId) {
        jdbc.update(
                "UPDATE settlements SET status = 'SCHEDULED',"
                        + " due_at = DATE_SUB(NOW(6), INTERVAL 1 MINUTE),"
                        + " worker_paid_amount = amount, owner_refund_amount = 0,"
                        + " deduction_base_minutes = 480, late_minutes = 0,"
                        + " early_leave_minutes = 0,"
                        + " calculation_reason = 'CHECKED_OUT',"
                        + " calculation_version = 'ATTENDANCE_V1',"
                        + " calculated_at = NOW(6) WHERE id = ?",
                settlementId
        );
        jdbc.update(
                "UPDATE settlements SET status = 'FAILED', retry_count = 5,"
                        + " failure_code = 'INTEGRITY_MISMATCH',"
                        + " last_failure_at = NOW(6), next_retry_at = NULL WHERE id = ?",
                settlementId
        );

        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE settlements SET failure_code = NULL WHERE id = ?",
                        settlementId
                )
        );
        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE settlements SET processing_at = NOW(6) WHERE id = ?",
                        settlementId
                )
        );
        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE settlements SET completed_at = NOW(6) WHERE id = ?",
                        settlementId
                )
        );
    }

    private void verifyDisputeTitle(JdbcTemplate jdbc, long workCaseId, long ownerId) {
        jdbc.update(
                "INSERT INTO disputes"
                        + " (work_case_id, requester_id, dispute_type, title, content)"
                        + " VALUES (?, ?, 'WAGE', '지급 금액 확인', '지급 내역을 확인해 주세요.')",
                workCaseId,
                ownerId
        );

        jdbc.update(
                "UPDATE disputes SET status = 'RESOLVED', resolution = '확인 완료',"
                        + " resolved_by_user_id = ?, resolved_at = NOW(6)"
                        + " WHERE work_case_id = ?",
                ownerId,
                workCaseId
        );

        assertThrows(
                DataAccessException.class,
                () -> insertDispute(jdbc, workCaseId, ownerId, "")
        );
        assertThrows(
                DataAccessException.class,
                () -> insertDispute(jdbc, workCaseId, ownerId, " 앞뒤 공백 ")
        );
        assertThrows(
                DataAccessException.class,
                () -> insertDispute(jdbc, workCaseId, ownerId, "가".repeat(101))
        );
    }

    private void insertDispute(
            JdbcTemplate jdbc,
            long workCaseId,
            long ownerId,
            String title) {
        jdbc.update(
                "INSERT INTO disputes"
                        + " (work_case_id, requester_id, dispute_type, title, content)"
                        + " VALUES (?, ?, 'WAGE', ?, '분쟁 내용')",
                workCaseId,
                ownerId,
                title
        );
    }

    private Fixture createFixture(JdbcTemplate jdbc, String token) {
        String ownerLogin = "it_schema_owner_" + token;
        String workerLogin = "it_schema_worker_" + token;
        String businessNumber = String.format(
                "%010d",
                Integer.toUnsignedLong(token.hashCode())
        );

        insertUser(jdbc, ownerLogin, "OWNER");
        insertUser(jdbc, workerLogin, "WORKER");
        long ownerId = idBy(jdbc, "users", "login_id", ownerLogin);
        long workerId = idBy(jdbc, "users", "login_id", workerLogin);

        jdbc.update(
                "INSERT INTO workplaces"
                        + " (owner_user_id, business_registration_number, name,"
                        + " representative_name, road_address, phone, status)"
                        + " VALUES (?, ?, '정산 스키마 사업장', '테스트 대표',"
                        + " '서울특별시 테스트로 1', '02-0000-0000', 'ACTIVE')",
                ownerId,
                businessNumber
        );
        long workplaceId = idBy(
                jdbc,
                "workplaces",
                "business_registration_number",
                businessNumber
        );

        long firstWorkCaseId = insertWorkCase(jdbc, ownerId, workerId, workplaceId, token + "a");
        long secondWorkCaseId = insertWorkCase(jdbc, ownerId, workerId, workplaceId, token + "b");
        long thirdWorkCaseId = insertWorkCase(jdbc, ownerId, workerId, workplaceId, token + "c");

        long firstSettlementId = insertWaitingSettlement(jdbc, firstWorkCaseId);
        long secondSettlementId = insertWaitingSettlement(jdbc, secondWorkCaseId);
        long thirdSettlementId = insertWaitingSettlement(jdbc, thirdWorkCaseId);

        return new Fixture(
                ownerId,
                workerId,
                workplaceId,
                firstWorkCaseId,
                secondWorkCaseId,
                thirdWorkCaseId,
                firstSettlementId,
                secondSettlementId,
                thirdSettlementId,
                ownerLogin,
                workerLogin
        );
    }

    private void insertUser(JdbcTemplate jdbc, String loginId, String role) {
        jdbc.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'schema-test-hash', '정산 스키마 테스트',"
                        + " ?, 'ACTIVE')",
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
                        + " (employer_id, worker_id, workplace_id, title,"
                        + " starts_at, ends_at, break_minutes, break_paid,"
                        + " workplace_name, workplace_address, allowed_radius_meters,"
                        + " agreed_wage, terms_version, status)"
                        + " VALUES (?, ?, ?, ?, '2030-01-01 09:00:00',"
                        + " '2030-01-01 18:00:00', 60, 0, '정산 스키마 사업장',"
                        + " '서울특별시 테스트로 1', 100, ?, 1, 'COMPLETED')",
                ownerId,
                workerId,
                workplaceId,
                title,
                WAGE
        );
        return idBy(jdbc, "work_cases", "title", title);
    }

    private long insertWaitingSettlement(JdbcTemplate jdbc, long workCaseId) {
        jdbc.update(
                "INSERT INTO settlements (work_case_id, amount, status)"
                        + " VALUES (?, ?, 'WAITING')",
                workCaseId,
                WAGE
        );
        return idBy(jdbc, "settlements", "work_case_id", workCaseId);
    }

    private void deleteFixture(JdbcTemplate jdbc, Fixture fixture) {
        jdbc.update(
                "DELETE FROM disputes WHERE work_case_id IN (?, ?, ?)",
                fixture.workCaseId(),
                fixture.refundWorkCaseId(),
                fixture.failedWorkCaseId()
        );
        jdbc.update(
                "DELETE FROM settlements WHERE work_case_id IN (?, ?, ?)",
                fixture.workCaseId(),
                fixture.refundWorkCaseId(),
                fixture.failedWorkCaseId()
        );
        jdbc.update(
                "DELETE FROM work_cases WHERE id IN (?, ?, ?)",
                fixture.workCaseId(),
                fixture.refundWorkCaseId(),
                fixture.failedWorkCaseId()
        );
        jdbc.update("DELETE FROM workplaces WHERE id = ?", fixture.workplaceId());
        jdbc.update(
                "DELETE FROM users WHERE id IN (?, ?)",
                fixture.ownerId(),
                fixture.workerId()
        );
    }

    private int count(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private long idBy(JdbcTemplate jdbc, String table, String column, Object value) {
        return jdbc.queryForObject(
                "SELECT id FROM " + table + " WHERE " + column + " = ?",
                Long.class,
                value
        );
    }

    private record Fixture(
            long ownerId,
            long workerId,
            long workplaceId,
            long workCaseId,
            long refundWorkCaseId,
            long failedWorkCaseId,
            long scheduledSettlementId,
            long refundSettlementId,
            long failedSettlementId,
            String ownerLogin,
            String workerLogin) {
    }
}
