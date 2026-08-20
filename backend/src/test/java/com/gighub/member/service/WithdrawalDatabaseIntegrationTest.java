package com.gighub.member.service;

import java.util.UUID;

import javax.sql.DataSource;

import com.gighub.auth.dto.LoginRequest;
import com.gighub.auth.service.AuthService;
import com.gighub.common.exception.AuthRequiredException;
import com.gighub.common.exception.ConflictException;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** AUTH-010 탈퇴가 현재 MySQL Schema와 실제 로그인 경계에서 성립하는지 확인합니다. */
@Tag("database")
class WithdrawalDatabaseIntegrationTest {

    private static final String PASSWORD = "withdraw-pass-188";

    @Test
    @Timeout(25)
    void withdrawalStopsLoginAndKeepsRowOnCurrentMysqlSchema() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            UserService userService = context.getBean(UserService.class);
            AuthService authService = context.getBean(AuthService.class);
            PasswordEncoder passwordEncoder = context.getBean(PasswordEncoder.class);

            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String loginId = "qa188" + suffix;

            try {
                Long userId = insertOwner(jdbcTemplate, passwordEncoder, loginId);

                verifyLoginWorksBeforeWithdrawal(authService, loginId, userId);
                verifyWrongPasswordChangesNothing(userService, jdbcTemplate, userId);
                verifyWalletBalanceBlocksWithdrawal(userService, jdbcTemplate, userId);
                verifyWithdrawalMarksStatusAndDeletedAt(userService, jdbcTemplate, userId);
                verifyWithdrawnUserCannotLogIn(authService, loginId);
                verifySecondWithdrawalIsRejected(userService, userId);
            } finally {
                jdbcTemplate.update(
                        "DELETE FROM wallets WHERE user_id ="
                                + " (SELECT id FROM users WHERE login_id = ?)", loginId);
                jdbcTemplate.update("DELETE FROM users WHERE login_id = ?", loginId);
            }
        }
    }

    private void verifyLoginWorksBeforeWithdrawal(
            AuthService authService, String loginId, Long userId) {
        assertEquals(
                userId,
                authService.login(loginRequest(loginId)).getPrincipal().getUserId());
    }

    private void verifyWrongPasswordChangesNothing(
            UserService userService, JdbcTemplate jdbcTemplate, Long userId) {
        assertThrows(
                ValidationException.class,
                () -> userService.withdraw(userId, "not-the-password"));

        assertEquals("ACTIVE", storedStatus(jdbcTemplate, userId));
        assertNull(storedDeletedAt(jdbcTemplate, userId));
    }

    /**
     * 잔액이 남아 있으면 탈퇴가 거부되고 계정은 그대로여야 합니다.
     *
     * <p>실제 {@code wallets} 행으로 확인합니다. 이 경로가 깨지면 사용자는 돈을 남긴 채
     * 로그인할 수 없게 되고, 잔액을 되찾을 화면이 사라집니다.</p>
     */
    private void verifyWalletBalanceBlocksWithdrawal(
            UserService userService, JdbcTemplate jdbcTemplate, Long userId) {
        jdbcTemplate.update(
                "INSERT INTO wallets (user_id, currency, available_balance, locked_balance)"
                        + " VALUES (?, 'KRW', 1000, 0)",
                userId);

        assertThrows(ConflictException.class, () -> userService.withdraw(userId, PASSWORD));
        assertEquals("ACTIVE", storedStatus(jdbcTemplate, userId));

        jdbcTemplate.update(
                "UPDATE wallets SET available_balance = 0 WHERE user_id = ?", userId);
    }

    private void verifyWithdrawalMarksStatusAndDeletedAt(
            UserService userService, JdbcTemplate jdbcTemplate, Long userId) {
        userService.withdraw(userId, PASSWORD);

        assertEquals("WITHDRAWN", storedStatus(jdbcTemplate, userId));
        // ck_users_deleted_at 이 WITHDRAWN 과 deleted_at 을 함께 요구한다.
        assertNotNull(storedDeletedAt(jdbcTemplate, userId));
        // 물리 삭제하지 않는다 — 감사·계약·금융 관계의 FK가 그대로 남아야 한다.
        assertEquals(1, (int) jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId));
    }

    private void verifyWithdrawnUserCannotLogIn(AuthService authService, String loginId) {
        assertThrows(
                AuthRequiredException.class,
                () -> authService.login(loginRequest(loginId)));
    }

    /** 이미 탈퇴한 계정은 ACTIVE 가드에 걸린다 — 비밀번호 변경과 같은 403 계약이다. */
    private void verifySecondWithdrawalIsRejected(UserService userService, Long userId) {
        assertThrows(ForbiddenException.class, () -> userService.withdraw(userId, PASSWORD));
    }

    private Long insertOwner(
            JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder, String loginId) {
        jdbcTemplate.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, phone, role, status)"
                        + " VALUES (?, ?, ?, '김사장', '01012345678', 'OWNER', 'ACTIVE')",
                loginId,
                loginId + "@example.com",
                passwordEncoder.encode(PASSWORD)
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE login_id = ?", Long.class, loginId);
    }

    private String storedStatus(JdbcTemplate jdbcTemplate, Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM users WHERE id = ?", String.class, userId);
    }

    private Object storedDeletedAt(JdbcTemplate jdbcTemplate, Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM users WHERE id = ?", Object.class, userId);
    }

    private LoginRequest loginRequest(String loginId) {
        LoginRequest request = new LoginRequest();
        request.setLoginId(loginId);
        request.setPassword(PASSWORD);
        request.setExpectedRole(UserRole.OWNER);
        return request;
    }
}
