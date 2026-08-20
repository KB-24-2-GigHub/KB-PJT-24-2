package com.gighub.badge.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import com.gighub.badge.mapper.result.BadgeEvidenceCountsRow;
import com.gighub.config.RootConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** 실제 MySQL에서 OWNER·WORKER 원천 집계 SQL이 SPEC-178-06 분모·분자 정의를 지키는지 검증합니다. */
@Tag("database")
class BadgeEvidenceSourceMapperDatabaseIntegrationTest {

    @Test
    @Timeout(60)
    void countsOwnerEvidenceExcludingDisputedSettlements() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            BadgeEvidenceSourceMapper mapper = context.getBean(BadgeEvidenceSourceMapper.class);
            String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            long ownerId = insertUser(jdbc, "it_bo_owner_" + token, token, "OWNER");
            long workerId = insertUser(jdbc, "it_bo_worker_" + token, token + "w", "WORKER");
            long workplaceId = insertWorkplace(jdbc, ownerId);

            try {
                // 정상: COMPLETED 정산 + 분쟁 없음
                long normalCase = insertWorkCase(jdbc, ownerId, workplaceId, workerId, "COMPLETED");
                insertSettlement(jdbc, normalCase, "COMPLETED");

                // 제외: 분쟁이 CANCELED/REJECTED가 아닌 채로 남아 있는 COMPLETED 정산
                long disputedCase = insertWorkCase(jdbc, ownerId, workplaceId, workerId, "COMPLETED");
                insertSettlement(jdbc, disputedCase, "COMPLETED");
                insertDispute(jdbc, disputedCase, "OPEN");

                // 제외: 아직 COMPLETED가 아닌 정산
                long waitingCase = insertWorkCase(jdbc, ownerId, workplaceId, workerId, "IN_PROGRESS");
                insertSettlement(jdbc, waitingCase, "WAITING");

                BadgeEvidenceCountsRow row = mapper.countOwnerEvidence(ownerId);

                assertEquals(2, row.getTotalCount());
                assertEquals(1, row.getNormalCount());
            } finally {
                deleteFixtures(jdbc, ownerId, workerId, workplaceId);
            }
        }
    }

    @Test
    @Timeout(60)
    void countsWorkerEvidenceTreatingLateCheckInAsAbnormal() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            BadgeEvidenceSourceMapper mapper = context.getBean(BadgeEvidenceSourceMapper.class);
            String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            long ownerId = insertUser(jdbc, "it_bw_owner_" + token, token, "OWNER");
            long workerId = insertUser(jdbc, "it_bw_worker_" + token, token + "w", "WORKER");
            long workplaceId = insertWorkplace(jdbc, ownerId);

            try {
                LocalDateTime startsAt = LocalDateTime.of(2026, 8, 1, 9, 0);

                // 정상: 정시 CHECK_IN 성공 + COMPLETED
                long onTimeCase = insertWorkCase(jdbc, ownerId, workplaceId, workerId, "COMPLETED", startsAt);
                insertCheckIn(jdbc, onTimeCase, workerId, startsAt.minusMinutes(5));

                // 비정상: 지각 CHECK_IN
                long lateCase = insertWorkCase(jdbc, ownerId, workplaceId, workerId, "COMPLETED", startsAt);
                insertCheckIn(jdbc, lateCase, workerId, startsAt.plusMinutes(10));

                // 비정상이지만 분모에는 포함: NO_SHOW
                insertWorkCase(jdbc, ownerId, workplaceId, workerId, "NO_SHOW", startsAt);

                // 분모 제외: CANCELED
                insertWorkCase(jdbc, ownerId, workplaceId, workerId, "CANCELED", startsAt);

                BadgeEvidenceCountsRow row = mapper.countWorkerEvidence(workerId);

                assertEquals(3, row.getTotalCount());
                assertEquals(1, row.getNormalCount());
            } finally {
                deleteFixtures(jdbc, ownerId, workerId, workplaceId);
            }
        }
    }

    private long insertUser(JdbcTemplate jdbc, String loginId, String emailToken, String role) {
        jdbc.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'mapper-test-hash', '뱃지 원천 Mapper 테스트', ?, 'ACTIVE')",
                loginId,
                emailToken + "@example.test",
                role);
        return jdbc.queryForObject(
                "SELECT id FROM users WHERE login_id = ?", Long.class, loginId);
    }

    private long insertWorkplace(JdbcTemplate jdbc, long ownerId) {
        String businessRegistrationNumber = String.format("%010d", ownerId % 10_000_000_000L);
        jdbc.update(
                "INSERT INTO workplaces"
                        + " (owner_user_id, business_registration_number, name, representative_name,"
                        + " road_address, phone)"
                        + " VALUES (?, ?, '뱃지 테스트 사업장', '테스트 대표', '테스트 주소', '01000000000')",
                ownerId,
                businessRegistrationNumber);
        return jdbc.queryForObject(
                "SELECT id FROM workplaces WHERE owner_user_id = ? ORDER BY id DESC LIMIT 1",
                Long.class,
                ownerId);
    }

    private long insertWorkCase(
            JdbcTemplate jdbc, long ownerId, long workplaceId, long workerId, String status) {
        return insertWorkCase(
                jdbc, ownerId, workplaceId, workerId, status, LocalDateTime.of(2026, 8, 1, 9, 0));
    }

    private long insertWorkCase(
            JdbcTemplate jdbc,
            long ownerId,
            long workplaceId,
            long workerId,
            String status,
            LocalDateTime startsAt) {
        LocalDateTime canceledAt = "CANCELED".equals(status) ? startsAt : null;
        jdbc.update(
                "INSERT INTO work_cases"
                        + " (employer_id, workplace_id, worker_id, title, starts_at, ends_at,"
                        + " workplace_name, workplace_address, agreed_wage, status, canceled_at)"
                        + " VALUES (?, ?, ?, '뱃지 테스트 근무', ?, ?, '테스트 사업장', '테스트 주소',"
                        + " 10000, ?, ?)",
                ownerId,
                workplaceId,
                workerId,
                startsAt,
                startsAt.plusHours(4),
                status,
                canceledAt);
        return jdbc.queryForObject(
                "SELECT id FROM work_cases WHERE employer_id = ? AND worker_id = ? AND status = ?"
                        + " ORDER BY id DESC LIMIT 1",
                Long.class,
                ownerId,
                workerId,
                status);
    }

    private void insertSettlement(JdbcTemplate jdbc, long workCaseId, String status) {
        // ck_settlements_lifecycle이 상태별 timestamp 조합을 강제하므로 COMPLETED는
        // due_at/processing_at/completed_at을 모두 채워야 한다.
        if ("COMPLETED".equals(status)) {
            LocalDateTime now = LocalDateTime.now();
            jdbc.update(
                    "INSERT INTO settlements"
                            + " (work_case_id, amount, worker_paid_amount, owner_refund_amount,"
                            + " calculation_reason, calculation_version, calculated_at, status,"
                            + " due_at, processing_at, completed_at)"
                            + " VALUES (?, 10000, 10000, 0, 'LEGACY', 'LEGACY', ?,"
                            + " 'COMPLETED', ?, ?, ?)",
                    workCaseId,
                    now,
                    now.minusMinutes(10),
                    now.minusMinutes(5),
                    now);
        } else {
            jdbc.update(
                    "INSERT INTO settlements (work_case_id, amount, status) VALUES (?, 10000, ?)",
                    workCaseId,
                    status);
        }
    }

    private void insertDispute(JdbcTemplate jdbc, long workCaseId, String status) {
        jdbc.update(
                "INSERT INTO disputes"
                        + " (work_case_id, requester_id, dispute_type, title, content, status)"
                        + " SELECT ?, employer_id, 'PAYMENT', '테스트 분쟁 제목', '테스트 분쟁', ?"
                        + " FROM work_cases WHERE id = ?",
                workCaseId,
                status,
                workCaseId);
    }

    private void insertCheckIn(
            JdbcTemplate jdbc, long workCaseId, long workerId, LocalDateTime attemptedAt) {
        jdbc.update(
                "INSERT INTO attendance_records"
                        + " (work_case_id, worker_id, attendance_type, captured_at, attempted_at, result)"
                        + " VALUES (?, ?, 'CHECK_IN', ?, ?, 'SUCCESS')",
                workCaseId,
                workerId,
                attemptedAt,
                attemptedAt);
    }

    private void deleteFixtures(JdbcTemplate jdbc, long ownerId, long workerId, long workplaceId) {
        jdbc.update(
                "DELETE FROM attendance_records WHERE worker_id = ?", workerId);
        jdbc.update(
                "DELETE FROM disputes WHERE work_case_id IN"
                        + " (SELECT id FROM work_cases WHERE employer_id = ? OR worker_id = ?)",
                ownerId, workerId);
        jdbc.update(
                "DELETE FROM settlements WHERE work_case_id IN"
                        + " (SELECT id FROM work_cases WHERE employer_id = ? OR worker_id = ?)",
                ownerId, workerId);
        jdbc.update("DELETE FROM work_cases WHERE employer_id = ? OR worker_id = ?", ownerId, workerId);
        jdbc.update("DELETE FROM workplaces WHERE id = ?", workplaceId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", ownerId, workerId);
    }
}
