package com.gighub.badge.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.gighub.badge.mapper.result.UserBadgeRow;
import com.gighub.config.RootConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** 실제 MySQL에서 Badge XML의 불변 Row 생성자 매핑을 검증합니다. */
@Tag("database")
class BadgeQueryMapperDatabaseIntegrationTest {

    private static final LocalDateTime AWARDED_AT = LocalDateTime.of(2026, 8, 10, 12, 30);

    @Test
    @Timeout(60)
    void mapsEveryBadgeColumnIntoThePersistenceRowConstructor() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            BadgeQueryMapper mapper = context.getBean(BadgeQueryMapper.class);
            String token = UUID.randomUUID().toString().replace("-", "");
            String loginId = "it_badge_row_" + token;

            try {
                long userId = insertUser(jdbc, loginId, token);
                long badgeId = insertBadge(jdbc, userId);

                List<UserBadgeRow> rows = mapper.findBadgesByUserId(userId);

                assertEquals(1, rows.size());
                UserBadgeRow row = rows.get(0);
                // String 열의 생성자 순서가 바뀌어도 컴파일되므로 각 값을 따로 고정합니다.
                assertEquals(badgeId, row.getId());
                assertEquals(userId, row.getUserId());
                assertEquals("TRUST_WORKER", row.getBadgeType());
                assertTrue(row.getEvidence().contains("rf-10-mapper-row"));
                assertEquals(AWARDED_AT, row.getAwardedAt());
            } finally {
                deleteFixtures(jdbc, loginId);
            }
        }
    }

    private long insertUser(JdbcTemplate jdbc, String loginId, String emailToken) {
        jdbc.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'mapper-test-hash', '뱃지 Mapper 테스트',"
                        + " 'WORKER', 'ACTIVE')",
                loginId,
                emailToken + "@example.test");
        return jdbc.queryForObject(
                "SELECT id FROM users WHERE login_id = ?", Long.class, loginId);
    }

    private long insertBadge(JdbcTemplate jdbc, long userId) {
        jdbc.update(
                "INSERT INTO user_badges (user_id, badge_type, evidence, awarded_at)"
                        + " VALUES (?, 'TRUST_WORKER',"
                        + " JSON_OBJECT('source', 'rf-10-mapper-row'), ?)",
                userId,
                AWARDED_AT);
        return jdbc.queryForObject(
                "SELECT id FROM user_badges WHERE user_id = ? AND badge_type = 'TRUST_WORKER'",
                Long.class,
                userId);
    }

    private void deleteFixtures(JdbcTemplate jdbc, String loginId) {
        jdbc.update(
                "DELETE FROM user_badges"
                        + " WHERE user_id IN (SELECT id FROM users WHERE login_id = ?)",
                loginId);
        jdbc.update("DELETE FROM users WHERE login_id = ?", loginId);
    }
}
