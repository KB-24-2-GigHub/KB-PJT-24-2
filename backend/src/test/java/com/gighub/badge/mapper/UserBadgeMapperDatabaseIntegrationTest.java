package com.gighub.badge.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gighub.badge.mapper.param.UserBadgeUpsertParam;
import com.gighub.config.ApiJsonMapper;
import com.gighub.config.RootConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** 실제 MySQL에서 uk_user_badges_user_type 기준 멱등 Upsert(재계산 시 갱신)를 검증합니다. */
@Tag("database")
class UserBadgeMapperDatabaseIntegrationTest {

    @Test
    @Timeout(60)
    void upsertUpdatesTheSameRowInsteadOfInsertingANewOne() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            UserBadgeMapper mapper = context.getBean(UserBadgeMapper.class);
            ObjectMapper objectMapper = ApiJsonMapper.create();
            String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            long userId = insertUser(jdbc, "it_ub_" + token, token);

            try {
                mapper.upsert(UserBadgeUpsertParam.of(
                        userId,
                        "TRUST_WORKER",
                        "{\"ruleVersion\":\"trust-badge-cumulative-10-20-30-v1\",\"level\":1}",
                        LocalDateTime.of(2026, 8, 1, 9, 0)));
                mapper.upsert(UserBadgeUpsertParam.of(
                        userId,
                        "TRUST_WORKER",
                        "{\"ruleVersion\":\"trust-badge-cumulative-10-20-30-v1\",\"level\":2}",
                        LocalDateTime.of(2026, 8, 2, 9, 0)));

                Integer rowCount = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM user_badges"
                                + " WHERE user_id = ? AND badge_type = 'TRUST_WORKER'",
                        Integer.class,
                        userId);
                String evidence = jdbc.queryForObject(
                        "SELECT evidence FROM user_badges"
                                + " WHERE user_id = ? AND badge_type = 'TRUST_WORKER'",
                        String.class,
                        userId);
                LocalDateTime awardedAt = jdbc.queryForObject(
                        "SELECT awarded_at FROM user_badges"
                                + " WHERE user_id = ? AND badge_type = 'TRUST_WORKER'",
                        LocalDateTime.class,
                        userId);
                JsonNode evidenceNode = objectMapper.readTree(evidence);

                assertEquals(1, rowCount);
                assertEquals(2, evidenceNode.path("level").asInt());
                assertEquals(LocalDateTime.of(2026, 8, 2, 9, 0), awardedAt);
            } finally {
                jdbc.update("DELETE FROM user_badges WHERE user_id = ?", userId);
                jdbc.update("DELETE FROM users WHERE id = ?", userId);
            }
        }
    }

    private long insertUser(JdbcTemplate jdbc, String loginId, String emailToken) {
        jdbc.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'mapper-test-hash', '뱃지 Upsert 테스트', 'WORKER', 'ACTIVE')",
                loginId,
                emailToken + "@example.test");
        return jdbc.queryForObject(
                "SELECT id FROM users WHERE login_id = ?", Long.class, loginId);
    }
}