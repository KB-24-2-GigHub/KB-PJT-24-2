package com.gighub.settlement;

import com.gighub.config.RootConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 승인된 #390 Migration의 FK·CHECK·활성 작업 Unique를 실제 MySQL에서 검증합니다. */
@Tag("database")
class DisputeAiReviewSchemaDatabaseIntegrationTest {

    @Test
    @Timeout(20)
    void schemaRejectsInvalidLifecycleAndPreservesClosedExecutionHistory() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            Fixture fixture = createFixture(jdbc);
            try {
                assertSchemaShape(jdbc);
                long firstReviewId = insertPending(jdbc, fixture.disputeId());

                assertThrows(
                        DuplicateKeyException.class,
                        () -> insertPending(jdbc, fixture.disputeId()));

                jdbc.update(
                        "UPDATE dispute_ai_reviews"
                                + " SET status = 'FAILED', failure_code = 'TEST_FAILURE',"
                                + " started_at = NOW(6), completed_at = NOW(6)"
                                + " WHERE id = ?",
                        firstReviewId
                );
                long secondReviewId = insertPending(jdbc, fixture.disputeId());

                assertThrows(
                        DataAccessException.class,
                        () -> jdbc.update(
                                "UPDATE dispute_ai_reviews"
                                        + " SET status = 'COMPLETED',"
                                        + " started_at = NOW(6), completed_at = NOW(6)"
                                        + " WHERE id = ?",
                                secondReviewId));
                assertEquals("PENDING", text(jdbc,
                        "SELECT status FROM dispute_ai_reviews WHERE id = ?",
                        secondReviewId));

                assertThrows(
                        DataIntegrityViolationException.class,
                        () -> jdbc.update(
                                "DELETE FROM disputes WHERE id = ?",
                                fixture.disputeId()));
                assertEquals(2, count(jdbc,
                        "SELECT COUNT(*) FROM dispute_ai_reviews WHERE dispute_id = ?",
                        fixture.disputeId()));
            } finally {
                deleteFixture(jdbc, fixture);
            }
        }
    }

    private static void assertSchemaShape(JdbcTemplate jdbc) {
        assertEquals(21, count(jdbc,
                "SELECT COUNT(*) FROM information_schema.columns"
                        + " WHERE table_schema = DATABASE()"
                        + " AND table_name = 'dispute_ai_reviews'"));
        assertEquals(1, count(jdbc,
                "SELECT COUNT(*) FROM information_schema.columns"
                        + " WHERE table_schema = DATABASE()"
                        + " AND table_name = 'dispute_ai_reviews'"
                        + " AND column_name = 'active_slot'"
                        + " AND extra LIKE '%STORED GENERATED%'"));
        assertEquals(2, count(jdbc,
                "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics"
                        + " WHERE table_schema = DATABASE()"
                        + " AND table_name = 'dispute_ai_reviews'"
                        + " AND index_name IN ('uk_dispute_ai_reviews_active',"
                        + " 'idx_dispute_ai_reviews_status_lease')"));
        assertEquals(1, count(jdbc,
                "SELECT COUNT(*) FROM information_schema.referential_constraints"
                        + " WHERE constraint_schema = DATABASE()"
                        + " AND table_name = 'dispute_ai_reviews'"
                        + " AND constraint_name = 'fk_dispute_ai_reviews_dispute'"
                        + " AND delete_rule = 'RESTRICT' AND update_rule = 'RESTRICT'"));
    }

    private static long insertPending(JdbcTemplate jdbc, long disputeId) {
        String requestKey = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO dispute_ai_reviews"
                        + " (dispute_id, request_key, status, source, provider, model,"
                        + " prompt_version, input_hash)"
                        + " VALUES (?, ?, 'PENDING', 'SIMULATED_LLM', 'FAKE',"
                        + " 'deterministic-dispute-demo-v1', 'dispute-review-v1', ?)",
                disputeId,
                requestKey,
                "a".repeat(64)
        );
        return jdbc.queryForObject(
                "SELECT id FROM dispute_ai_reviews WHERE request_key = ?",
                Long.class,
                requestKey
        );
    }

    private static Fixture createFixture(JdbcTemplate jdbc) {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String ownerLogin = "it_ai_schema_owner_" + token;
        String workerLogin = "it_ai_schema_worker_" + token;
        insertUser(jdbc, ownerLogin, "OWNER");
        insertUser(jdbc, workerLogin, "WORKER");
        long ownerId = idBy(jdbc, "users", "login_id", ownerLogin);
        long workerId = idBy(jdbc, "users", "login_id", workerLogin);
        String businessNumber = String.format(
                "%010d", Integer.toUnsignedLong(token.hashCode()));
        jdbc.update(
                "INSERT INTO workplaces"
                        + " (owner_user_id, business_registration_number, name,"
                        + " representative_name, road_address, phone, status)"
                        + " VALUES (?, ?, 'AI 이력 테스트 사업장', '테스트 대표',"
                        + " '서울특별시 테스트로 1', '02-0000-0000', 'ACTIVE')",
                ownerId,
                businessNumber
        );
        long workplaceId = idBy(
                jdbc, "workplaces", "business_registration_number", businessNumber);
        String workTitle = "ai-schema-" + token;
        jdbc.update(
                "INSERT INTO work_cases"
                        + " (employer_id, worker_id, workplace_id, title, starts_at, ends_at,"
                        + " break_minutes, break_paid, workplace_name, workplace_address,"
                        + " allowed_radius_meters, agreed_wage, terms_version, status)"
                        + " VALUES (?, ?, ?, ?, '2030-01-01 09:00:00',"
                        + " '2030-01-01 18:00:00', 60, 0, 'AI 이력 테스트 사업장',"
                        + " '서울특별시 테스트로 1', 100, 120000, 1, 'COMPLETED')",
                ownerId,
                workerId,
                workplaceId,
                workTitle
        );
        long workCaseId = idBy(jdbc, "work_cases", "title", workTitle);
        jdbc.update(
                "INSERT INTO disputes"
                        + " (work_case_id, requester_id, dispute_type, title, content, status)"
                        + " VALUES (?, ?, 'WAGE', '임금 확인', '지급 여부 확인', 'OPEN')",
                workCaseId,
                workerId
        );
        long disputeId = idBy(jdbc, "disputes", "work_case_id", workCaseId);
        return new Fixture(ownerId, workerId, workplaceId, workCaseId, disputeId);
    }

    private static void deleteFixture(JdbcTemplate jdbc, Fixture fixture) {
        jdbc.update("DELETE FROM dispute_ai_reviews WHERE dispute_id = ?", fixture.disputeId());
        jdbc.update("DELETE FROM disputes WHERE id = ?", fixture.disputeId());
        jdbc.update("DELETE FROM work_cases WHERE id = ?", fixture.workCaseId());
        jdbc.update("DELETE FROM workplaces WHERE id = ?", fixture.workplaceId());
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", fixture.ownerId(), fixture.workerId());
    }

    private static void insertUser(JdbcTemplate jdbc, String loginId, String role) {
        jdbc.update(
                "INSERT INTO users (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'ai-schema-test-hash', 'AI 이력 테스트', ?, 'ACTIVE')",
                loginId,
                loginId + "@example.test",
                role
        );
    }

    private static long idBy(JdbcTemplate jdbc, String table, String column, Object value) {
        return jdbc.queryForObject(
                "SELECT id FROM " + table + " WHERE " + column + " = ?",
                Long.class,
                value
        );
    }

    private static int count(JdbcTemplate jdbc, String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private static String text(JdbcTemplate jdbc, String sql, Object argument) {
        return jdbc.queryForObject(sql, String.class, argument);
    }

    private record Fixture(
            long ownerId,
            long workerId,
            long workplaceId,
            long workCaseId,
            long disputeId) {
    }
}
