package com.gighub.auth.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.gighub.auth.dto.LoginRequest;
import com.gighub.auth.dto.SignupRequest;
import com.gighub.auth.service.impl.AuthServiceImpl;
import com.gighub.common.exception.AuthRequiredException;
import com.gighub.common.exception.ConflictException;
import com.gighub.config.RootConfig;
import com.gighub.member.domain.UserRole;
import com.gighub.member.mapper.UserMapper;
import com.gighub.wallet.service.WalletProvisionService;
import com.gighub.workplace.service.WorkplaceOwnershipService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;

@Tag("database")
class AuthFlowDatabaseIntegrationTest {

    @Test
    @Timeout(25)
    void signupLoginRollbackAndConcurrentDuplicateUseCurrentMysqlSchema() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            AuthService authService = context.getBean(AuthService.class);
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String loginId = "qa71" + suffix;
            String rollbackLoginId = "qa71r" + suffix;
            String concurrentLoginId = "qa71c" + suffix;
            List<String> fixtureLoginIds = List.of(
                    loginId,
                    rollbackLoginId,
                    concurrentLoginId
            );

            try {
                verifySuccessfulSignupAndLogin(jdbcTemplate, authService, loginId);
                verifyRollbackWhenWalletCreationFails(
                        context,
                        jdbcTemplate,
                        rollbackLoginId
                );
                verifyConcurrentDuplicateLeavesOneUserAndWallet(
                        jdbcTemplate,
                        authService,
                        concurrentLoginId
                );
            } finally {
                deleteFixtures(jdbcTemplate, fixtureLoginIds);
            }
        }
    }

    private void verifySuccessfulSignupAndLogin(
            JdbcTemplate jdbcTemplate,
            AuthService authService,
            String loginId) {
        SignupRequest signupRequest = signupRequest(loginId);

        Long userId = authService.signup(signupRequest);

        assertEquals(1, count(jdbcTemplate,
                "SELECT COUNT(*) FROM users WHERE id = ? AND login_id = ? AND status = 'ACTIVE'",
                userId,
                loginId));
        assertEquals(1, count(jdbcTemplate,
                "SELECT COUNT(*) FROM wallets WHERE user_id = ? AND currency = 'KRW'",
                userId));
        String passwordHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE id = ?",
                String.class,
                userId
        );
        assertNotEquals("test-pass-71", passwordHash);

        LoginResult loginResult = authService.login(loginRequest(loginId));
        assertEquals(userId, loginResult.getPrincipal().getUserId());
        assertEquals(UserRole.OWNER, loginResult.getPrincipal().getRole());
        assertTrue(loginResult.isNeedsWorkplaceSetup());

        jdbcTemplate.update("UPDATE users SET status = 'LOCKED' WHERE id = ?", userId);
        assertThrows(AuthRequiredException.class, () -> authService.login(loginRequest(loginId)));
        assertThrows(ConflictException.class, () -> authService.signup(signupRequest));
    }

    private void verifyRollbackWhenWalletCreationFails(
            AnnotationConfigApplicationContext context,
            JdbcTemplate jdbcTemplate,
            String loginId) {
        WalletProvisionService failingWalletProvision = mock(WalletProvisionService.class);
        doThrow(new IllegalStateException("wallet failure"))
                .when(failingWalletProvision).provisionKrwWallet(anyLong());
        AuthServiceImpl target = new AuthServiceImpl(
                context.getBean(WorkplaceOwnershipService.class),
                context.getBean(UserMapper.class),
                failingWalletProvision,
                context.getBean(PasswordEncoder.class)
        );
        TransactionInterceptor transactionInterceptor = new TransactionInterceptor();
        transactionInterceptor.setTransactionManager(
                context.getBean(PlatformTransactionManager.class)
        );
        transactionInterceptor.setTransactionAttributeSource(
                new AnnotationTransactionAttributeSource()
        );
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(transactionInterceptor);
        AuthService transactionalService = (AuthService) proxyFactory.getProxy();

        assertThrows(
                IllegalStateException.class,
                () -> transactionalService.signup(signupRequest(loginId))
        );
        assertEquals(0, count(
                jdbcTemplate,
                "SELECT COUNT(*) FROM users WHERE login_id = ?",
                loginId
        ));
    }

    private void verifyConcurrentDuplicateLeavesOneUserAndWallet(
            JdbcTemplate jdbcTemplate,
            AuthService authService,
            String loginId) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        SignupRequest firstRequest = signupRequest(loginId);
        SignupRequest secondRequest = signupRequest(loginId);

        try {
            Future<Object> first = executor.submit(
                    () -> signupAfterStart(authService, firstRequest, start)
            );
            Future<Object> second = executor.submit(
                    () -> signupAfterStart(authService, secondRequest, start)
            );
            start.countDown();

            List<Object> results = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );
            assertEquals(1, results.stream().filter(Long.class::isInstance).count());
            assertEquals(1, results.stream().filter(ConflictException.class::isInstance).count());
            assertEquals(1, count(
                    jdbcTemplate,
                    "SELECT COUNT(*) FROM users WHERE login_id = ?",
                    loginId
            ));
            assertEquals(1, count(
                    jdbcTemplate,
                    "SELECT COUNT(*) FROM wallets w JOIN users u ON u.id = w.user_id"
                            + " WHERE u.login_id = ? AND w.currency = 'KRW'",
                    loginId
            ));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private Object signupAfterStart(
            AuthService authService,
            SignupRequest request,
            CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            return authService.signup(request);
        } catch (ConflictException exception) {
            return exception;
        }
    }

    private SignupRequest signupRequest(String loginId) {
        SignupRequest request = new SignupRequest();
        request.setLoginId(loginId);
        request.setPassword("test-pass-71");
        request.setPasswordConfirm("test-pass-71");
        request.setName("인증 통합 테스트");
        request.setEmail(loginId + "@example.invalid");
        request.setRole(UserRole.OWNER);
        return request;
    }

    private LoginRequest loginRequest(String loginId) {
        LoginRequest request = new LoginRequest();
        request.setLoginId(loginId);
        request.setPassword("test-pass-71");
        request.setExpectedRole(UserRole.OWNER);
        return request;
    }

    private int count(JdbcTemplate jdbcTemplate, String sql, Object... arguments) {
        Integer result = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        return result == null ? 0 : result;
    }

    private void deleteFixtures(JdbcTemplate jdbcTemplate, List<String> loginIds) {
        for (String loginId : loginIds) {
            jdbcTemplate.update(
                    "DELETE FROM wallets WHERE user_id IN"
                            + " (SELECT id FROM users WHERE login_id = ?)",
                    loginId
            );
            jdbcTemplate.update("DELETE FROM users WHERE login_id = ?", loginId);
        }
    }
}
