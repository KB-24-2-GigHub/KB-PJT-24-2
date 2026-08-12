package com.gighub.badge;

import com.gighub.config.RootConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("database")
class UserBadgeTypeSchemaDatabaseIntegrationTest {

    @Test
    void allowsApprovedBadgeTypesOnlyAndKeepsOneRowPerUserAndType() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbcTemplate =
                    new JdbcTemplate(context.getBean(DataSource.class));
            String token = UUID.randomUUID().toString().replace("-", "");
            String loginId = "it_badge_" + token;

            try {
                long userId = insertUser(jdbcTemplate, loginId, token);

                insertBadge(jdbcTemplate, userId, "TRUST_WORKER");
                insertBadge(jdbcTemplate, userId, "TRUST_OWNER");

                assertEquals(
                        2,
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM user_badges WHERE user_id = ?",
                                Integer.class,
                                userId
                        )
                );

                // 같은 사용자·유형의 뱃지는 한 행만 유지한다.
                assertThrows(
                        DuplicateKeyException.class,
                        () -> insertBadge(jdbcTemplate, userId, "TRUST_WORKER")
                );

                // 승인 목록 밖의 badge_type은 저장할 수 없어야 한다.
                assertThrows(
                        DataAccessException.class,
                        () -> insertBadge(jdbcTemplate, userId, "TRUST_UNLISTED")
                );
            } finally {
                deleteFixtures(jdbcTemplate, loginId);
            }
        }
    }

    private long insertUser(
            JdbcTemplate jdbcTemplate,
            String loginId,
            String emailToken) {
        jdbcTemplate.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'schema-test-hash', '뱃지 스키마 테스트',"
                        + " 'WORKER', 'ACTIVE')",
                loginId,
                emailToken + "@example.test"
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE login_id = ?",
                Long.class,
                loginId
        );
    }

    private void insertBadge(
            JdbcTemplate jdbcTemplate,
            long userId,
            String badgeType) {
        jdbcTemplate.update(
                "INSERT INTO user_badges (user_id, badge_type, evidence)"
                        + " VALUES (?, ?, JSON_OBJECT())",
                userId,
                badgeType
        );
    }

    private void deleteFixtures(JdbcTemplate jdbcTemplate, String loginId) {
        jdbcTemplate.update(
                "DELETE FROM user_badges"
                        + " WHERE user_id IN (SELECT id FROM users WHERE login_id = ?)",
                loginId
        );
        jdbcTemplate.update("DELETE FROM users WHERE login_id = ?", loginId);
    }
}
