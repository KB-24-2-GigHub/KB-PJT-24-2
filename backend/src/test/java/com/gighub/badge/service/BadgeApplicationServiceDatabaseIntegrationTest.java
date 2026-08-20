package com.gighub.badge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gighub.badge.service.result.BadgeCalculationResult;
import com.gighub.config.ApiJsonMapper;
import com.gighub.config.RootConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** 실제 MySQL에서 잠금→집계→Upsert 전체 흐름이 SPEC-178-06대로 끝까지 동작하는지 검증합니다. */
@Tag("database")
class BadgeApplicationServiceDatabaseIntegrationTest {

    @Test
    @Timeout(60)
    void recalculatesOwnerBadgeAndPersistsLevelOne() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            BadgeApplicationService service = context.getBean(BadgeApplicationService.class);
            String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            long ownerId = insertUser(jdbc, "it_bas_owner_" + token, token);
            long workerId = insertUser(jdbc, "it_bas_worker_" + token, token + "w");
            long workplaceId = insertWorkplace(jdbc, ownerId);

            try {
                // 10건 COMPLETED 정산, 전부 정상 → 누적 10 / 정상 10 → 1단계
                for (int i = 0; i < 10; i++) {
                    long workCaseId = insertWorkCase(jdbc, ownerId, workplaceId, workerId);
                    insertSettlement(jdbc, workCaseId);
                }

                BadgeCalculationResult result = service.recalculate(ownerId);

                assertEquals("TRUST_OWNER", result.getBadgeType());
                assertEquals(1, result.getLevel());
                assertEquals(10, result.getTotalCount());
                assertEquals(10, result.getNormalCount());
                assertEquals(10, result.getRemainingToNextLevel());

                String badgeType = jdbc.queryForObject(
                        "SELECT badge_type FROM user_badges WHERE user_id = ?",
                        String.class,
                        ownerId);
                String evidence = jdbc.queryForObject(
                        "SELECT evidence FROM user_badges WHERE user_id = ?",
                        String.class,
                        ownerId);
                ObjectMapper objectMapper = ApiJsonMapper.create();
                JsonNode evidenceNode = objectMapper.readTree(evidence);

                assertEquals("TRUST_OWNER", badgeType);
                assertEquals(1, evidenceNode.path("level").asInt());
                assertEquals(10, evidenceNode.path("totalCount").asInt());
            } finally {
                deleteFixtures(jdbc, ownerId, workerId, workplaceId);
            }
        }
    }

    private long insertUser(JdbcTemplate jdbc, String loginId, String emailToken) {
        jdbc.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'app-service-test-hash', '뱃지 Application Service 테스트',"
                        + " 'OWNER', 'ACTIVE')",
                loginId,
                emailToken + "@example.test");
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

    private long insertWorkCase(JdbcTemplate jdbc, long ownerId, long workplaceId, long workerId) {
        LocalDateTime startsAt = LocalDateTime.of(2026, 8, 1, 9, 0);
        jdbc.update(
                "INSERT INTO work_cases"
                        + " (employer_id, workplace_id, worker_id, title, starts_at, ends_at,"
                        + " workplace_name, workplace_address, agreed_wage, status)"
                        + " VALUES (?, ?, ?, '뱃지 테스트 근무', ?, ?, '테스트 사업장', '테스트 주소',"
                        + " 10000, 'COMPLETED')",
                ownerId,
                workplaceId,
                workerId,
                startsAt,
                startsAt.plusHours(4));
        return jdbc.queryForObject(
                "SELECT id FROM work_cases WHERE employer_id = ? AND worker_id = ?"
                        + " ORDER BY id DESC LIMIT 1",
                Long.class,
                ownerId,
                workerId);
    }

    private void insertSettlement(JdbcTemplate jdbc, long workCaseId) {
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
    }

    private void deleteFixtures(JdbcTemplate jdbc, long ownerId, long workerId, long workplaceId) {
        jdbc.update("DELETE FROM user_badges WHERE user_id = ?", ownerId);
        jdbc.update(
                "DELETE FROM settlements WHERE work_case_id IN"
                        + " (SELECT id FROM work_cases WHERE employer_id = ? OR worker_id = ?)",
                ownerId, workerId);
        jdbc.update("DELETE FROM work_cases WHERE employer_id = ? OR worker_id = ?", ownerId, workerId);
        jdbc.update("DELETE FROM workplaces WHERE id = ?", workplaceId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", ownerId, workerId);
    }
}
