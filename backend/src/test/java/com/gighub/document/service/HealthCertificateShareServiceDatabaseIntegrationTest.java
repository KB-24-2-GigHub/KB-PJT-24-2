package com.gighub.document.service;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.ConflictException;
import com.gighub.config.RootConfig;
import com.gighub.member.domain.UserRole;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 활성 공유 유일성을 실제 MySQL 제약으로 확인합니다.
 *
 * <p>단위 테스트는 {@code DuplicateKeyException}을 흉내 내므로 "제약이 정말 두 번째 삽입을
 * 막는가"와 "동시 요청에서도 한 건만 남는가"를 증명하지 못합니다. 여기서는 프록시된 Service
 * Bean을 실제 DB에 대고 호출한다.</p>
 */
@Tag("database")
class HealthCertificateShareServiceDatabaseIntegrationTest {

    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 9, 1, 9, 0);
    private static final LocalDateTime ENDS_AT = LocalDateTime.of(2026, 9, 1, 18, 0);

    private String businessNumberPrefix;

    @Test
    @Timeout(90)
    void keepsOneActiveShareUnderRepeatedAndConcurrentRequests() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            HealthCertificateShareService service =
                    context.getBean(HealthCertificateShareService.class);

            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            businessNumberPrefix = String.format(
                    "%06d", ThreadLocalRandom.current().nextInt(1_000_000));

            long ownerId = insertUser(jdbc, "ss181o" + suffix, "김대표", "OWNER");
            long workerId = insertUser(jdbc, "ss181a" + suffix, "이알바", "WORKER");
            long workplaceId = insertWorkplace(jdbc, ownerId, 1);
            long workCaseId = insertWorkCase(jdbc, ownerId, workerId, workplaceId, "ACCEPTED");
            long documentId = insertHealthCertificate(jdbc, workerId);
            AuthPrincipal worker = new AuthPrincipal(workerId, UserRole.WORKER, "이알바");

            try {
                long firstShareId = service.share(worker, documentId, workplaceId);
                assertEquals(1, countActiveShares(jdbc, documentId));

                // 같은 사업장 재요청은 409다. 멱등 성공이 아니다.
                assertThrows(ConflictException.class,
                        () -> service.share(worker, documentId, workplaceId));
                assertEquals(1, countActiveShares(jdbc, documentId));

                // 철회 뒤 재공유는 기존 행을 되살리지 않고 새 행을 만든다.
                jdbc.update(
                        "UPDATE document_shares SET status = 'REVOKED', revoked_at = NOW(6)"
                                + " WHERE id = ?", firstShareId);
                long secondShareId = service.share(worker, documentId, workplaceId);
                assertNotEquals(firstShareId, secondShareId);
                assertEquals(1, countActiveShares(jdbc, documentId));
                assertEquals(2, countAllShares(jdbc, documentId));

                jdbc.update("UPDATE document_shares SET status = 'REVOKED', revoked_at = NOW(6)"
                        + " WHERE document_id = ?", documentId);

                // 동시 두 요청에서도 ACTIVE 행은 하나뿐이고 나머지는 409다.
                assertEquals(1, countConcurrentSuccesses(service, worker, documentId, workplaceId));
                assertEquals(1, countActiveShares(jdbc, documentId));
            } finally {
                jdbc.update("DELETE FROM document_shares WHERE document_id = ?", documentId);
                jdbc.update("DELETE FROM documents WHERE id = ?", documentId);
                jdbc.update("DELETE FROM notifications WHERE work_case_id = ?", workCaseId);
                jdbc.update("DELETE FROM work_cases WHERE id = ?", workCaseId);
                jdbc.update("DELETE FROM workplaces WHERE id = ?", workplaceId);
                jdbc.update("DELETE FROM users WHERE id IN (?, ?)", ownerId, workerId);
            }
        }
    }

    private int countConcurrentSuccesses(
            HealthCertificateShareService service,
            AuthPrincipal worker,
            long documentId,
            long workplaceId) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> attempt = () -> {
                start.await();
                try {
                    service.share(worker, documentId, workplaceId);
                    return true;
                } catch (RuntimeException rejected) {
                    return false;
                }
            };

            Future<Boolean> first = executor.submit(attempt);
            Future<Boolean> second = executor.submit(attempt);
            start.countDown();

            int successes = 0;
            if (first.get(30, TimeUnit.SECONDS)) {
                successes++;
            }
            if (second.get(30, TimeUnit.SECONDS)) {
                successes++;
            }
            return successes;
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private int countActiveShares(JdbcTemplate jdbc, long documentId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM document_shares"
                        + " WHERE document_id = ? AND status = 'ACTIVE'",
                Integer.class, documentId);
    }

    private int countAllShares(JdbcTemplate jdbc, long documentId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM document_shares WHERE document_id = ?",
                Integer.class, documentId);
    }

    private long insertUser(JdbcTemplate jdbc, String loginId, String name, String role) {
        jdbc.update(
                "INSERT INTO users (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'test-hash', ?, ?, 'ACTIVE')",
                loginId, loginId + "@example.test", name, role);
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

    private long insertHealthCertificate(JdbcTemplate jdbc, long ownerId) {
        jdbc.update(
                "INSERT INTO documents"
                        + " (created_by_user_id, owner_user_id, work_case_id, document_type,"
                        + " status, issued_on, expires_on)"
                        + " VALUES (?, ?, NULL, 'HEALTH_CERTIFICATE', 'ACTIVE', ?, ?)",
                ownerId, ownerId, LocalDate.now().minusDays(1), LocalDate.now().plusYears(1));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertWorkCase(
            JdbcTemplate jdbc, long employerId, long workerId, long workplaceId, String status) {
        jdbc.update(
                "INSERT INTO work_cases"
                        + " (employer_id, worker_id, workplace_id, title, starts_at, ends_at,"
                        + " break_minutes, break_paid, workplace_name, workplace_address,"
                        + " allowed_radius_meters, agreed_wage, terms_version, status)"
                        + " VALUES (?, ?, ?, '공유 생성 테스트', ?, ?, 0, 0, '강남점',"
                        + " '서울 테스트로 1', 100, 90000, 1, ?)",
                employerId, workerId, workplaceId, STARTS_AT, ENDS_AT, status);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
