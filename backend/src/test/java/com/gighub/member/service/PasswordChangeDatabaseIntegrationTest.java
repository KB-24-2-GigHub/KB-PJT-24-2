package com.gighub.member.service;

import java.time.LocalDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import com.gighub.auth.dto.LoginRequest;
import com.gighub.auth.service.AuthService;
import com.gighub.common.exception.AuthRequiredException;
import com.gighub.common.exception.ForbiddenException;
import com.gighub.common.exception.ValidationException;
import com.gighub.config.RootConfig;
import com.gighub.member.domain.UserRole;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AUTH-009 비밀번호 변경이 현재 MySQL Schema와 실제 로그인 경계에서 성립하는지 확인합니다. */
@Tag("database")
class PasswordChangeDatabaseIntegrationTest {

    private static final String CURRENT_PASSWORD = "current-pass-187";
    private static final String NEW_PASSWORD = "new-pass-187";

    @Test
    @Timeout(25)
    void changedPasswordReplacesLoginCredentialOnCurrentMysqlSchema() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            UserService userService = context.getBean(UserService.class);
            AuthService authService = context.getBean(AuthService.class);
            PasswordEncoder passwordEncoder = context.getBean(PasswordEncoder.class);

            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String loginId = "qa187" + suffix;

            try {
                Long userId = insertOwner(jdbcTemplate, passwordEncoder, loginId);

                verifyWrongCurrentPasswordChangesNothing(userService, jdbcTemplate, userId);
                verifyChangedPasswordIsStoredAsHash(
                        userService, jdbcTemplate, passwordEncoder, userId);
                verifyOldCredentialStopsWorkingAndNewOneWorks(authService, loginId, userId);
                verifyNonActiveAccountCannotChangePassword(userService, jdbcTemplate, userId);
            } finally {
                jdbcTemplate.update("DELETE FROM users WHERE login_id = ?", loginId);
            }
        }
    }

    private void verifyWrongCurrentPasswordChangesNothing(
            UserService userService, JdbcTemplate jdbcTemplate, Long userId) {
        String storedBefore = storedHash(jdbcTemplate, userId);

        assertThrows(
                ValidationException.class,
                () -> userService.changePassword(userId, "not-the-current-pass", NEW_PASSWORD));

        assertEquals(storedBefore, storedHash(jdbcTemplate, userId));
    }

    private void verifyChangedPasswordIsStoredAsHash(
            UserService userService,
            JdbcTemplate jdbcTemplate,
            PasswordEncoder passwordEncoder,
            Long userId) {
        LocalDateTime updatedBefore = storedUpdatedAt(jdbcTemplate, userId);

        userService.changePassword(userId, CURRENT_PASSWORD, NEW_PASSWORD);

        // users.updated_at 은 ON UPDATE CURRENT_TIMESTAMP(6) 이라 비밀번호 교체 시각이 남는다.
        // 이 단언이 그 Schema 속성에 의존한다는 사실을 드러내 두면, 나중에 누가 걷어낼 때 잡힌다.
        assertTrue(storedUpdatedAt(jdbcTemplate, userId).isAfter(updatedBefore));

        String stored = storedHash(jdbcTemplate, userId);
        assertNotEquals(NEW_PASSWORD, stored);
        assertTrue(stored.startsWith("$2"));
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, stored));
        assertFalse(passwordEncoder.matches(CURRENT_PASSWORD, stored));
    }

    private void verifyOldCredentialStopsWorkingAndNewOneWorks(
            AuthService authService, String loginId, Long userId) {
        assertThrows(
                AuthRequiredException.class,
                () -> authService.login(loginRequest(loginId, CURRENT_PASSWORD)));

        assertEquals(
                userId,
                authService.login(loginRequest(loginId, NEW_PASSWORD)).getPrincipal().getUserId());
    }

    private void verifyNonActiveAccountCannotChangePassword(
            UserService userService, JdbcTemplate jdbcTemplate, Long userId) {
        jdbcTemplate.update("UPDATE users SET status = 'LOCKED' WHERE id = ?", userId);
        String storedBefore = storedHash(jdbcTemplate, userId);

        assertThrows(
                ForbiddenException.class,
                () -> userService.changePassword(userId, NEW_PASSWORD, "third-pass-187"));

        assertEquals(storedBefore, storedHash(jdbcTemplate, userId));
    }

    private Long insertOwner(
            JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder, String loginId) {
        jdbcTemplate.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, phone, role, status)"
                        + " VALUES (?, ?, ?, '김사장', '01012345678', 'OWNER', 'ACTIVE')",
                loginId,
                loginId + "@example.com",
                passwordEncoder.encode(CURRENT_PASSWORD)
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE login_id = ?", Long.class, loginId);
    }

    private LocalDateTime storedUpdatedAt(JdbcTemplate jdbcTemplate, Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT updated_at FROM users WHERE id = ?", LocalDateTime.class, userId);
    }

    private String storedHash(JdbcTemplate jdbcTemplate, Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE id = ?", String.class, userId);
    }

    private LoginRequest loginRequest(String loginId, String password) {
        LoginRequest request = new LoginRequest();
        request.setLoginId(loginId);
        request.setPassword(password);
        request.setExpectedRole(UserRole.OWNER);
        return request;
    }
}
