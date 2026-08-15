package com.gighub.document.mapper;

import com.gighub.config.RootConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 사업장 단위 공유 철회가 정확히 그 사업장의 ACTIVE 공유만 바꾸는지 실제 MySQL에서
 * 확인합니다.
 *
 * <p>같은 근로자가 같은 보건증을 두 사업장에 공유한 상태에서 하나만 철회하면 다른 하나는
 * 그대로여야 합니다. {@code document_id}만으로 걸렀다면 이 Test가 실패합니다(오철회).</p>
 */
@Tag("database")
class RevokeActiveSharesByWorkplaceDatabaseIntegrationTest {

    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 9, 1, 9, 0);
    private static final LocalDateTime ENDS_AT = LocalDateTime.of(2026, 9, 1, 18, 0);

    private String businessNumberPrefix;

    @Test
    @Timeout(60)
    void revokesOnlyTheTargetWorkplaceShareAndLeavesOthersUntouched() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            ContractDocumentWriteMapper mapper = context.getBean(ContractDocumentWriteMapper.class);

            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            businessNumberPrefix = String.format(
                    "%06d", ThreadLocalRandom.current().nextInt(1_000_000));

            long workerId = insertUser(jdbc, "rv181a" + suffix, "이알바");
            long firstOwnerId = insertUser(jdbc, "rv181o" + suffix, "김대표");
            long secondOwnerId = insertUser(jdbc, "rv181p" + suffix, "최대표");

            long firstWorkplaceId = insertWorkplace(jdbc, firstOwnerId, 1);
            long secondWorkplaceId = insertWorkplace(jdbc, secondOwnerId, 2);
            long firstWorkCaseId = insertWorkCase(jdbc, firstOwnerId, workerId, firstWorkplaceId);
            long secondWorkCaseId = insertWorkCase(jdbc, secondOwnerId, workerId, secondWorkplaceId);

            long documentId = insertDocument(jdbc, workerId);
            long firstShareId = insertActiveShare(jdbc, documentId, firstWorkCaseId, firstOwnerId);
            long secondShareId = insertActiveShare(jdbc, documentId, secondWorkCaseId, secondOwnerId);

            try {
                LocalDateTime revokedAt = LocalDateTime.of(2026, 8, 15, 12, 0);
                int updated = mapper.revokeActiveSharesByWorkplace(
                        documentId, firstWorkplaceId, revokedAt);

                assertEquals(1, updated);
                assertEquals("REVOKED", statusOf(jdbc, firstShareId));
                assertEquals(revokedAt, revokedAtOf(jdbc, firstShareId));
                // 다른 사업장의 ACTIVE 공유는 건드리지 않는다.
                assertEquals("ACTIVE", statusOf(jdbc, secondShareId));

                // 이미 철회된 대상 재요청은 0행이며 오류가 아니다.
                assertEquals(0, mapper.revokeActiveSharesByWorkplace(
                        documentId, firstWorkplaceId, revokedAt.plusMinutes(1)));

                // 애초에 공유한 적 없는 사업장도 0행이며 오류가 아니다.
                long unrelatedWorkplaceId = insertWorkplace(jdbc, firstOwnerId, 3);
                assertEquals(0, mapper.revokeActiveSharesByWorkplace(
                        documentId, unrelatedWorkplaceId, revokedAt));
                jdbc.update("DELETE FROM workplaces WHERE id = ?", unrelatedWorkplaceId);
            } finally {
                jdbc.update("DELETE FROM document_shares WHERE document_id = ?", documentId);
                jdbc.update("DELETE FROM documents WHERE id = ?", documentId);
                jdbc.update("DELETE FROM work_cases WHERE id IN (?, ?)",
                        firstWorkCaseId, secondWorkCaseId);
                jdbc.update("DELETE FROM workplaces WHERE id IN (?, ?)",
                        firstWorkplaceId, secondWorkplaceId);
                jdbc.update("DELETE FROM users WHERE id IN (?, ?, ?)",
                        workerId, firstOwnerId, secondOwnerId);
            }
        }
    }

    private String statusOf(JdbcTemplate jdbc, long shareId) {
        return jdbc.queryForObject(
                "SELECT status FROM document_shares WHERE id = ?", String.class, shareId);
    }

    private LocalDateTime revokedAtOf(JdbcTemplate jdbc, long shareId) {
        return jdbc.queryForObject(
                "SELECT revoked_at FROM document_shares WHERE id = ?",
                LocalDateTime.class, shareId);
    }

    private long insertUser(JdbcTemplate jdbc, String loginId, String name) {
        jdbc.update(
                "INSERT INTO users (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'test-hash', ?, 'WORKER', 'ACTIVE')",
                loginId, loginId + "@example.test", name);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertWorkplace(JdbcTemplate jdbc, long ownerId, int sequence) {
        jdbc.update(
                "INSERT INTO workplaces"
                        + " (owner_user_id, business_registration_number, name,"
                        + " representative_name, road_address, phone, status)"
                        + " VALUES (?, ?, '강남점', '김대표', '서울 테스트로 1',"
                        + " '0212345678', 'ACTIVE')",
                ownerId, businessNumberPrefix + String.format("%04d", sequence));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertWorkCase(
            JdbcTemplate jdbc, long employerId, long workerId, long workplaceId) {
        jdbc.update(
                "INSERT INTO work_cases"
                        + " (employer_id, worker_id, workplace_id, title, starts_at, ends_at,"
                        + " break_minutes, break_paid, workplace_name, workplace_address,"
                        + " allowed_radius_meters, agreed_wage, terms_version, status)"
                        + " VALUES (?, ?, ?, '공유 철회 테스트', ?, ?, 0, 0, '강남점',"
                        + " '서울 테스트로 1', 100, 90000, 1, 'ACCEPTED')",
                employerId, workerId, workplaceId, STARTS_AT, ENDS_AT);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertDocument(JdbcTemplate jdbc, long ownerId) {
        jdbc.update(
                "INSERT INTO documents"
                        + " (created_by_user_id, owner_user_id, work_case_id, document_type,"
                        + " status, issued_on, expires_on)"
                        + " VALUES (?, ?, NULL, 'HEALTH_CERTIFICATE', 'ACTIVE', ?, ?)",
                ownerId, ownerId, LocalDate.now().minusDays(1), LocalDate.now().plusYears(1));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertActiveShare(
            JdbcTemplate jdbc, long documentId, long workCaseId, long sharedWithUserId) {
        jdbc.update(
                "INSERT INTO document_shares"
                        + " (document_id, work_case_id, shared_with_user_id, purpose, status)"
                        + " VALUES (?, ?, ?, 'HEALTH_CERTIFICATE', 'ACTIVE')",
                documentId, workCaseId, sharedWithUserId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
