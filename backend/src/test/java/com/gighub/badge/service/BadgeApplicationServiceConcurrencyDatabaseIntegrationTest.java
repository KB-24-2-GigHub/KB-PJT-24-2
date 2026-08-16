package com.gighub.badge.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import com.gighub.badge.service.result.BadgeCalculationResult;
import com.gighub.config.RootConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 같은 사용자에게 동시에 들어온 두 재계산 요청이 안전하게 직렬화되는지 확인합니다.
 *
 * <p>{@code lockById}(FOR UPDATE)가 계산·Upsert 구간 전체를 감싸는지 검증하는 회귀
 * 방지용 테스트입니다. 두 요청이 읽는 원천 데이터 자체는 테스트 동안 바뀌지 않으므로
 * "오래된 계산이 최신 계산을 덮어쓰는" 상황 자체를 재현하지는 않고, 대신 동시 접근 시
 * 예외·교착상태 없이 끝나는지와 {@code user_badges}에 중복 없이 정확히 한 행만 남는지를
 * 확인합니다.</p>
 */
@Tag("database")
class BadgeApplicationServiceConcurrencyDatabaseIntegrationTest {

    @Test
    @Timeout(60)
    void concurrentRecalculationsForTheSameUserProduceExactlyOneRow() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            BadgeApplicationService service = context.getBean(BadgeApplicationService.class);
            String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            long ownerId = insertUser(jdbc, "it_bac_owner_" + token, token);
            long workerId = insertUser(jdbc, "it_bac_worker_" + token, token + "w");
            long workplaceId = insertWorkplace(jdbc, ownerId);

            try {
                for (int i = 0; i < 10; i++) {
                    long workCaseId = insertWorkCase(jdbc, ownerId, workplaceId, workerId);
                    insertSettlement(jdbc, workCaseId);
                }

                CountDownLatch readyLatch = new CountDownLatch(2);
                CountDownLatch startLatch = new CountDownLatch(1);
                ExecutorService executor = Executors.newFixedThreadPool(2);
                try {
                    List<Future<BadgeCalculationResult>> futures = IntStream.range(0, 2)
                            .mapToObj(i -> executor.submit(() -> {
                                readyLatch.countDown();
                                startLatch.await();
                                return service.recalculate(ownerId);
                            }))
                            .toList();

                    readyLatch.await();
                    startLatch.countDown();

                    for (Future<BadgeCalculationResult> future : futures) {
                        BadgeCalculationResult result = assertDoesNotThrow(
                                () -> future.get(30, TimeUnit.SECONDS));
                        assertEquals("TRUST_OWNER", result.getBadgeType());
                        assertEquals(1, result.getLevel());
                    }
                } finally {
                    executor.shutdownNow();
                }

                Integer rowCount = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM user_badges"
                                + " WHERE user_id = ? AND badge_type = 'TRUST_OWNER'",
                        Integer.class,
                        ownerId);
                assertEquals(1, rowCount, "동시 재계산이 중복 행을 만들면 안 됩니다.");
            } finally {
                deleteFixtures(jdbc, ownerId, workerId, workplaceId);
            }
        }
    }

    private long insertUser(JdbcTemplate jdbc, String loginId, String emailToken) {
        jdbc.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'concurrency-test-hash', '뱃지 동시성 테스트',"
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
                        + " VALUES (?, ?, '뱃지 동시성 테스트 사업장', '테스트 대표', '테스트 주소',"
                        + " '01000000000')",
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
                        + " VALUES (?, ?, ?, '뱃지 동시성 테스트 근무', ?, ?, '테스트 사업장',"
                        + " '테스트 주소', 10000, 'COMPLETED')",
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
                        + " (work_case_id, amount, status, due_at, processing_at, completed_at)"
                        + " VALUES (?, 10000, 'COMPLETED', ?, ?, ?)",
                workCaseId,
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
