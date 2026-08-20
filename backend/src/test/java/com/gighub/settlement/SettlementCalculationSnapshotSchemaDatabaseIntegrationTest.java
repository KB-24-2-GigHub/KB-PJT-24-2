package com.gighub.settlement;

import com.gighub.config.RootConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("database")
class SettlementCalculationSnapshotSchemaDatabaseIntegrationTest {

    private static final long WAGE = 100_005L;

    @Test
    void enforcesCalculationSnapshotFormulaLifecycleAndDeductionBase() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            String token = UUID.randomUUID().toString().replace("-", "");
            Fixture fixture = createFixture(jdbc, token);

            try {
                verifySchemaObjects(jdbc);
                verifyWorkCaseDeductionBase(jdbc, fixture);
                verifyCheckedOutSnapshot(jdbc, fixture);
                verifyFullAndZeroPayoutBoundaries(jdbc, fixture);
                verifyRefundAndLegacyShapes(jdbc, fixture);
            } finally {
                deleteFixture(jdbc, fixture);
            }
        }
    }

    private void verifySchemaObjects(JdbcTemplate jdbc) {
        assertEquals(
                5,
                count(
                        jdbc,
                        "SELECT COUNT(*)"
                                + " FROM information_schema.columns"
                                + " WHERE table_schema = DATABASE()"
                                + " AND table_name = 'settlements'"
                                + " AND column_name IN"
                                + " ('worker_paid_amount', 'owner_refund_amount',"
                                + " 'deduction_base_minutes', 'late_minutes',"
                                + " 'early_leave_minutes')"
                                + " AND column_type = 'bigint unsigned'"
                                + " AND is_nullable = 'YES'"
                )
        );
        assertEquals(
                2,
                count(
                        jdbc,
                        "SELECT COUNT(*)"
                                + " FROM information_schema.columns"
                                + " WHERE table_schema = DATABASE()"
                                + " AND table_name = 'settlements'"
                                + " AND ((column_name = 'calculation_reason'"
                                + " AND column_type = 'varchar(32)')"
                                + " OR (column_name = 'calculation_version'"
                                + " AND column_type = 'varchar(20)'))"
                                + " AND character_set_name = 'ascii'"
                                + " AND collation_name = 'ascii_bin'"
                                + " AND is_nullable = 'YES'"
                )
        );
        assertEquals(
                1,
                count(
                        jdbc,
                        "SELECT COUNT(*)"
                                + " FROM information_schema.columns"
                                + " WHERE table_schema = DATABASE()"
                                + " AND table_name = 'settlements'"
                                + " AND column_name = 'calculated_at'"
                                + " AND data_type = 'datetime'"
                                + " AND datetime_precision = 6"
                                + " AND is_nullable = 'YES'"
                )
        );
        assertEquals(
                5,
                count(
                        jdbc,
                        "SELECT COUNT(*)"
                                + " FROM information_schema.table_constraints"
                                + " WHERE constraint_schema = DATABASE()"
                                + " AND ((table_name = 'settlements'"
                                + " AND constraint_name IN"
                                + " ('ck_settlements_calculation_snapshot_shape',"
                                + " 'ck_settlements_calculation_amounts',"
                                + " 'ck_settlements_calculation_formula',"
                                + " 'ck_settlements_calculation_lifecycle'))"
                                + " OR (table_name = 'work_cases'"
                                + " AND constraint_name ="
                                + " 'ck_work_cases_deduction_base_minutes'))"
                                + " AND constraint_type = 'CHECK'"
                                + " AND enforced = 'YES'"
                )
        );
    }

    private void verifyWorkCaseDeductionBase(
            JdbcTemplate jdbc,
            Fixture fixture) {
        long paidBreakCaseId = insertWorkCase(
                jdbc,
                fixture,
                "paid-break",
                LocalDateTime.of(2030, 1, 1, 9, 0),
                LocalDateTime.of(2030, 1, 1, 10, 0),
                60,
                true,
                50_000L,
                "DRAFT"
        );

        // 유급 휴게는 분모에서 빼지 않으므로 예정 시간과 같아도 허용합니다.
        assertEquals(
                1,
                count(
                        jdbc,
                        "SELECT COUNT(*) FROM work_cases"
                                + " WHERE id = ? AND break_minutes = 60"
                                + " AND break_paid = 1",
                        paidBreakCaseId
                )
        );

        // 같은 휴게를 무급으로 바꾸면 D=0이 되어 DB에서 거절합니다.
        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE work_cases SET break_paid = 0 WHERE id = ?",
                        paidBreakCaseId
                )
        );

        long unpaidBreakCaseId = insertWorkCase(
                jdbc,
                fixture,
                "unpaid-break",
                LocalDateTime.of(2030, 1, 2, 9, 0),
                LocalDateTime.of(2030, 1, 2, 10, 0),
                59,
                false,
                50_000L,
                "DRAFT"
        );

        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE work_cases SET break_minutes = 60 WHERE id = ?",
                        unpaidBreakCaseId
                )
        );
        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE work_cases SET ends_at ="
                                + " '2030-01-02 09:00:30' WHERE id = ?",
                        unpaidBreakCaseId
                )
        );
    }

    private void verifyCheckedOutSnapshot(
            JdbcTemplate jdbc,
            Fixture fixture) {
        long workCaseId = insertWorkCase(
                jdbc,
                fixture,
                "checked-out",
                LocalDateTime.of(2030, 2, 1, 9, 0),
                LocalDateTime.of(2030, 2, 1, 17, 0),
                60,
                false,
                WAGE,
                "COMPLETED"
        );
        insertWaitingSettlement(jdbc, workCaseId, WAGE);

        // WAITING은 Snapshot 전 상태를 허용하지만, SCHEDULED부터는 Snapshot이 필수입니다.
        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE settlements SET status = 'SCHEDULED',"
                                + " due_at = NOW(6) WHERE work_case_id = ?",
                        workCaseId
                )
        );

        jdbc.update(
                "UPDATE settlements SET status = 'SCHEDULED', due_at = NOW(6),"
                        + " worker_paid_amount = 88810, owner_refund_amount = 11195,"
                        + " deduction_base_minutes = 420, late_minutes = 31,"
                        + " early_leave_minutes = 16,"
                        + " calculation_reason = 'CHECKED_OUT',"
                        + " calculation_version = 'ATTENDANCE_V1',"
                        + " calculated_at = NOW(6) WHERE work_case_id = ?",
                workCaseId
        );

        assertEquals(
                1,
                count(
                        jdbc,
                        "SELECT COUNT(*) FROM settlements"
                                + " WHERE work_case_id = ?"
                                + " AND worker_paid_amount = 88810"
                                + " AND owner_refund_amount = 11195"
                                + " AND worker_paid_amount + owner_refund_amount = amount",
                        workCaseId
                )
        );

        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE settlements SET worker_paid_amount = 88820,"
                                + " owner_refund_amount = 11185"
                                + " WHERE work_case_id = ?",
                        workCaseId
                )
        );
        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE settlements SET calculation_version = 'LEGACY'"
                                + " WHERE work_case_id = ?",
                        workCaseId
                )
        );

        long partialCaseId = insertWorkCase(
                jdbc,
                fixture,
                "partial-shape",
                LocalDateTime.of(2030, 2, 2, 9, 0),
                LocalDateTime.of(2030, 2, 2, 17, 0),
                0,
                false,
                WAGE,
                "IN_PROGRESS"
        );
        insertWaitingSettlement(jdbc, partialCaseId, WAGE);
        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE settlements SET worker_paid_amount = amount"
                                + " WHERE work_case_id = ?",
                        partialCaseId
                )
        );
    }

    private void verifyFullAndZeroPayoutBoundaries(
            JdbcTemplate jdbc,
            Fixture fixture) {
        long fullCaseId = insertWorkCase(
                jdbc,
                fixture,
                "full-payout",
                LocalDateTime.of(2030, 3, 1, 9, 0),
                LocalDateTime.of(2030, 3, 1, 17, 0),
                60,
                false,
                WAGE,
                "COMPLETED"
        );
        insertCheckedOutSettlement(
                jdbc,
                fullCaseId,
                WAGE,
                WAGE,
                0L,
                420L,
                0L,
                0L
        );

        long zeroCaseId = insertWorkCase(
                jdbc,
                fixture,
                "zero-payout",
                LocalDateTime.of(2030, 3, 2, 9, 0),
                LocalDateTime.of(2030, 3, 2, 17, 0),
                60,
                false,
                WAGE,
                "COMPLETED"
        );
        insertCheckedOutSettlement(
                jdbc,
                zeroCaseId,
                WAGE,
                0L,
                WAGE,
                420L,
                420L,
                0L
        );

        assertEquals(
                2,
                count(
                        jdbc,
                        "SELECT COUNT(*) FROM settlements"
                                + " WHERE work_case_id IN (?, ?)"
                                + " AND worker_paid_amount + owner_refund_amount = amount",
                        fullCaseId,
                        zeroCaseId
                )
        );
    }

    private void verifyRefundAndLegacyShapes(
            JdbcTemplate jdbc,
            Fixture fixture) {
        long noShowCaseId = insertWorkCase(
                jdbc,
                fixture,
                "no-show",
                LocalDateTime.of(2030, 4, 1, 9, 0),
                LocalDateTime.of(2030, 4, 1, 17, 0),
                60,
                true,
                80_000L,
                "NO_SHOW"
        );
        jdbc.update(
                "INSERT INTO settlements"
                        + " (work_case_id, amount, status, worker_paid_amount,"
                        + " owner_refund_amount, deduction_base_minutes,"
                        + " late_minutes, early_leave_minutes,"
                        + " calculation_reason, calculation_version, calculated_at)"
                        + " VALUES (?, 80000, 'WAITING', 0, 80000, 480, 0, 0,"
                        + " 'NO_SHOW', 'ATTENDANCE_V1', NOW(6))",
                noShowCaseId
        );
        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE settlements SET calculation_reason = 'no_show'"
                                + " WHERE work_case_id = ?",
                        noShowCaseId
                )
        );

        long missingCaseId = insertWorkCase(
                jdbc,
                fixture,
                "check-out-missing",
                LocalDateTime.of(2030, 4, 2, 9, 0),
                LocalDateTime.of(2030, 4, 2, 17, 0),
                60,
                false,
                90_000L,
                "CHECK_OUT_MISSING"
        );
        jdbc.update(
                "INSERT INTO settlements"
                        + " (work_case_id, amount, status, worker_paid_amount,"
                        + " owner_refund_amount, deduction_base_minutes,"
                        + " late_minutes, early_leave_minutes,"
                        + " calculation_reason, calculation_version, calculated_at)"
                        + " VALUES (?, 90000, 'WAITING', 0, 90000, 420, 5, 0,"
                        + " 'CHECK_OUT_MISSING', 'ATTENDANCE_V1', NOW(6))",
                missingCaseId
        );

        long legacyCaseId = insertWorkCase(
                jdbc,
                fixture,
                "legacy",
                LocalDateTime.of(2030, 4, 3, 9, 0),
                LocalDateTime.of(2030, 4, 3, 17, 0),
                0,
                false,
                70_000L,
                "COMPLETED"
        );
        jdbc.update(
                "INSERT INTO settlements"
                        + " (work_case_id, amount, status, due_at, processing_at,"
                        + " completed_at, worker_paid_amount, owner_refund_amount,"
                        + " calculation_reason, calculation_version, calculated_at)"
                        + " VALUES (?, 70000, 'COMPLETED', NOW(6), NOW(6), NOW(6),"
                        + " 70000, 0, 'LEGACY', 'LEGACY', NOW(6))",
                legacyCaseId
        );

        long invalidLegacyCaseId = insertWorkCase(
                jdbc,
                fixture,
                "invalid-legacy",
                LocalDateTime.of(2030, 4, 4, 9, 0),
                LocalDateTime.of(2030, 4, 4, 17, 0),
                0,
                false,
                70_000L,
                "IN_PROGRESS"
        );
        insertWaitingSettlement(jdbc, invalidLegacyCaseId, 70_000L);
        assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                        "UPDATE settlements SET worker_paid_amount = amount,"
                                + " owner_refund_amount = 0, calculation_reason = 'LEGACY',"
                                + " calculation_version = 'LEGACY', calculated_at = NOW(6)"
                                + " WHERE work_case_id = ?",
                        invalidLegacyCaseId
                )
        );
    }

    private Fixture createFixture(JdbcTemplate jdbc, String token) {
        String identityToken = token.substring(0, 12);
        String ownerLogin = "m424_owner_" + identityToken;
        String workerLogin = "m424_worker_" + identityToken;
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
        return new Fixture(
                ownerId,
                workerId,
                workplaceId,
                ownerLogin,
                workerLogin,
                "M424-" + token + "-"
        );
    }

    private void insertUser(JdbcTemplate jdbc, String loginId, String role) {
        jdbc.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'schema-test-hash', '정산 스키마 테스트',"
                        + " ?, 'ACTIVE')",
                loginId,
                loginId + "@example.invalid",
                role
        );
    }

    private long insertWorkCase(
            JdbcTemplate jdbc,
            Fixture fixture,
            String suffix,
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            int breakMinutes,
            boolean breakPaid,
            long wage,
            String status) {
        String title = fixture.titlePrefix() + suffix;
        jdbc.update(
                "INSERT INTO work_cases"
                        + " (employer_id, worker_id, workplace_id, title,"
                        + " starts_at, ends_at, break_minutes, break_paid,"
                        + " workplace_name, workplace_address, allowed_radius_meters,"
                        + " agreed_wage, terms_version, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, '정산 스키마 사업장',"
                        + " '서울특별시 테스트로 1', 100, ?, 1, ?)",
                fixture.ownerId(),
                fixture.workerId(),
                fixture.workplaceId(),
                title,
                startsAt,
                endsAt,
                breakMinutes,
                breakPaid ? 1 : 0,
                wage,
                status
        );
        return idBy(jdbc, "work_cases", "title", title);
    }

    private void insertWaitingSettlement(
            JdbcTemplate jdbc,
            long workCaseId,
            long amount) {
        jdbc.update(
                "INSERT INTO settlements (work_case_id, amount, status)"
                        + " VALUES (?, ?, 'WAITING')",
                workCaseId,
                amount
        );
    }

    private void insertCheckedOutSettlement(
            JdbcTemplate jdbc,
            long workCaseId,
            long amount,
            long paidAmount,
            long refundAmount,
            long baseMinutes,
            long lateMinutes,
            long earlyLeaveMinutes) {
        jdbc.update(
                "INSERT INTO settlements"
                        + " (work_case_id, amount, status, due_at,"
                        + " worker_paid_amount, owner_refund_amount,"
                        + " deduction_base_minutes, late_minutes, early_leave_minutes,"
                        + " calculation_reason, calculation_version, calculated_at)"
                        + " VALUES (?, ?, 'SCHEDULED', NOW(6), ?, ?, ?, ?, ?,"
                        + " 'CHECKED_OUT', 'ATTENDANCE_V1', NOW(6))",
                workCaseId,
                amount,
                paidAmount,
                refundAmount,
                baseMinutes,
                lateMinutes,
                earlyLeaveMinutes
        );
    }

    private void deleteFixture(JdbcTemplate jdbc, Fixture fixture) {
        jdbc.update(
                "DELETE FROM settlements WHERE work_case_id IN"
                        + " (SELECT id FROM work_cases WHERE title LIKE ?)",
                fixture.titlePrefix() + "%"
        );
        jdbc.update(
                "DELETE FROM work_cases WHERE title LIKE ?",
                fixture.titlePrefix() + "%"
        );
        jdbc.update("DELETE FROM workplaces WHERE id = ?", fixture.workplaceId());
        jdbc.update(
                "DELETE FROM users WHERE id IN (?, ?)",
                fixture.ownerId(),
                fixture.workerId()
        );
    }

    private int count(JdbcTemplate jdbc, String sql, Object... arguments) {
        Integer result = jdbc.queryForObject(sql, Integer.class, arguments);
        return result == null ? 0 : result;
    }

    private long idBy(
            JdbcTemplate jdbc,
            String table,
            String column,
            Object value) {
        Long result = jdbc.queryForObject(
                "SELECT id FROM " + table + " WHERE " + column + " = ?",
                Long.class,
                value
        );
        if (result == null) {
            throw new IllegalStateException("스키마 테스트 Fixture ID를 찾을 수 없습니다.");
        }
        return result;
    }

    private record Fixture(
            long ownerId,
            long workerId,
            long workplaceId,
            String ownerLogin,
            String workerLogin,
            String titlePrefix) {
    }
}
