package com.gighub.settlement.service;

import com.gighub.config.RootConfig;
import com.gighub.settlement.dto.ScheduledPayoutCandidate;
import com.gighub.settlement.mapper.SettlementMapper;
import com.gighub.settlement.service.policy.SettlementPayoutDecision;
import com.gighub.settlement.service.policy.SettlementPayoutRejectedException;
import com.gighub.settlement.service.result.SettlementResult;
import com.gighub.wallet.idempotency.WalletIdempotencyKeys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SETTLE-003 후보 조회·선점·재시도 기록이 실제 MySQL 잠금·CHECK 제약과 맞물려 동작하는지
 * 검증한다. {@code SettlementIntegrityDatabaseIntegrationTest}는 수동 승인과 원자 지급 자체를
 * 이미 다루므로, 여기서는 #172가 새로 추가한 후보 조회·잠금·감사 기록만 다룬다.
 */
@Tag("database")
class SettlementScheduledPayoutDatabaseIntegrationTest {

    private static final Long WAGE = 300_000L;

    @Test
    @Timeout(20)
    void candidateQueryExcludesOpenDisputeAndRespectsDueAtBoundary() {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            SettlementMapper settlementMapper = context.getBean(SettlementMapper.class);
            SettlementFixture dueFixture = createFixture(jdbcTemplate);
            SettlementFixture futureFixture = createFixture(jdbcTemplate);
            SettlementFixture disputedFixture = createFixture(jdbcTemplate);
            LocalDateTime now = jdbcTemplate.queryForObject("SELECT NOW(6)", LocalDateTime.class);

            try {
                // due_at == now 경계는 포함돼야 한다 (계약: due_at <= NOW(6)).
                setDueAt(jdbcTemplate, dueFixture, now);
                setDueAt(jdbcTemplate, futureFixture, now.plusMinutes(1));
                setDueAt(jdbcTemplate, disputedFixture, now.minusMinutes(1));
                openDispute(jdbcTemplate, disputedFixture);

                List<Long> candidates =
                        settlementMapper.findScheduledPayoutCandidateIds(now, 100);

                assertTrue(candidates.contains(dueFixture.settlementId()));
                assertFalse(candidates.contains(futureFixture.settlementId()));
                assertFalse(candidates.contains(disputedFixture.settlementId()));
            } finally {
                deleteFixture(jdbcTemplate, dueFixture);
                deleteFixture(jdbcTemplate, futureFixture);
                deleteFixture(jdbcTemplate, disputedFixture);
            }
        }
    }

    @Test
    @Timeout(20)
    void candidateQueryExcludesRowsWaitingForNextRetry() {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            SettlementMapper settlementMapper = context.getBean(SettlementMapper.class);
            SettlementFixture fixture = createFixture(jdbcTemplate);
            LocalDateTime now = jdbcTemplate.queryForObject("SELECT NOW(6)", LocalDateTime.class);

            try {
                setDueAt(jdbcTemplate, fixture, now.minusHours(1));
                jdbcTemplate.update(
                        "UPDATE settlements SET retry_count = 1,"
                                + " failure_code = 'SCHEDULER_LOCK_CONTENTION',"
                                + " last_failure_at = NOW(6),"
                                + " next_retry_at = DATE_ADD(NOW(6), INTERVAL 10 MINUTE)"
                                + " WHERE id = ?",
                        fixture.settlementId());

                List<Long> candidates =
                        settlementMapper.findScheduledPayoutCandidateIds(now, 100);

                assertFalse(candidates.contains(fixture.settlementId()));
            } finally {
                deleteFixture(jdbcTemplate, fixture);
            }
        }
    }

    @Test
    @Timeout(20)
    void attemptPayoutLocksCandidateAndCompletesSettlement() {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            SettlementScheduledPayoutService payoutService =
                    context.getBean(SettlementScheduledPayoutService.class);
            SettlementFixture fixture = createFixture(jdbcTemplate);
            LocalDateTime now = jdbcTemplate.queryForObject("SELECT NOW(6)", LocalDateTime.class);
            setDueAt(jdbcTemplate, fixture, now.minusSeconds(1));

            try {
                SettlementResult result =
                        payoutService.attemptPayout(fixture.settlementId(), now);

                assertNotNull(result);
                assertEquals("COMPLETED", result.getStatus());
                assertEquals(WAGE, result.getWorkerPaidAmount());
                assertEquals("COMPLETED", text(
                        jdbcTemplate,
                        "SELECT status FROM settlements WHERE id = ?",
                        fixture.settlementId()));
                assertNull(nullableLong(
                        jdbcTemplate,
                        "SELECT approved_by_user_id FROM settlements WHERE id = ?",
                        fixture.settlementId()));
            } finally {
                deleteFixture(jdbcTemplate, fixture);
            }
        }
    }

    @Test
    @Timeout(20)
    void attemptPayoutSkipsWhenNotYetDue() {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            SettlementScheduledPayoutService payoutService =
                    context.getBean(SettlementScheduledPayoutService.class);
            SettlementFixture fixture = createFixture(jdbcTemplate);
            LocalDateTime now = jdbcTemplate.queryForObject("SELECT NOW(6)", LocalDateTime.class);
            setDueAt(jdbcTemplate, fixture, now.plusHours(1));

            try {
                SettlementResult result =
                        payoutService.attemptPayout(fixture.settlementId(), now);

                assertNull(result);
                assertEquals("SCHEDULED", text(
                        jdbcTemplate,
                        "SELECT status FROM settlements WHERE id = ?",
                        fixture.settlementId()));
            } finally {
                deleteFixture(jdbcTemplate, fixture);
            }
        }
    }

    @Test
    @Timeout(25)
    void concurrentAttemptPayoutHasExactlyOneWinnerAndOneSkip() throws Exception {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            SettlementScheduledPayoutService payoutService =
                    context.getBean(SettlementScheduledPayoutService.class);
            SettlementFixture fixture = createFixture(jdbcTemplate);
            LocalDateTime now = jdbcTemplate.queryForObject("SELECT NOW(6)", LocalDateTime.class);
            setDueAt(jdbcTemplate, fixture, now.minusSeconds(1));
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);

            Callable<SettlementResult> attempt = () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("동시 Scheduler 시작 신호를 기다리지 못했습니다.");
                }
                return payoutService.attemptPayout(fixture.settlementId(), now);
            };

            try {
                Future<SettlementResult> first = executor.submit(attempt);
                Future<SettlementResult> second = executor.submit(attempt);
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();

                int completed = 0;
                int skipped = 0;
                for (Future<SettlementResult> future : List.of(first, second)) {
                    SettlementResult result = future.get(15, TimeUnit.SECONDS);
                    if (result == null) {
                        skipped++;
                    } else {
                        completed++;
                    }
                }

                assertEquals(1, completed);
                assertEquals(1, skipped);
                assertEquals("COMPLETED", text(
                        jdbcTemplate,
                        "SELECT status FROM settlements WHERE id = ?",
                        fixture.settlementId()));
            } finally {
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
                deleteFixture(jdbcTemplate, fixture);
            }
        }
    }

    @Test
    @Timeout(20)
    void recordFailureKeepsScheduledAndSchedulesNextRetry() {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            SettlementScheduledPayoutService payoutService =
                    context.getBean(SettlementScheduledPayoutService.class);
            SettlementFixture fixture = createFixture(jdbcTemplate);
            LocalDateTime now = jdbcTemplate.queryForObject("SELECT NOW(6)", LocalDateTime.class);
            setDueAt(jdbcTemplate, fixture, now.minusSeconds(1));

            try {
                boolean recorded = payoutService.recordFailure(
                        fixture.settlementId(),
                        new CannotAcquireLockException("lock timeout"),
                        now);

                assertTrue(recorded);
                assertEquals("SCHEDULED", text(
                        jdbcTemplate,
                        "SELECT status FROM settlements WHERE id = ?",
                        fixture.settlementId()));
                assertEquals(1, count(
                        jdbcTemplate,
                        "SELECT COUNT(*) FROM settlements"
                                + " WHERE id = ? AND retry_count = 1"
                                + " AND failure_code = 'SCHEDULER_LOCK_CONTENTION'"
                                + " AND next_retry_at IS NOT NULL",
                        fixture.settlementId()));
            } finally {
                deleteFixture(jdbcTemplate, fixture);
            }
        }
    }

    @Test
    @Timeout(20)
    void recordFailureMarksFailedOnFifthAttempt() {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            SettlementScheduledPayoutService payoutService =
                    context.getBean(SettlementScheduledPayoutService.class);
            SettlementFixture fixture = createFixture(jdbcTemplate);
            LocalDateTime now = jdbcTemplate.queryForObject("SELECT NOW(6)", LocalDateTime.class);
            setDueAt(jdbcTemplate, fixture, now.minusSeconds(1));
            jdbcTemplate.update(
                    "UPDATE settlements SET retry_count = 4,"
                            + " failure_code = 'SCHEDULER_LOCK_CONTENTION',"
                            + " last_failure_at = NOW(6),"
                            + " next_retry_at = NOW(6)"
                            + " WHERE id = ?",
                    fixture.settlementId());

            try {
                boolean recorded = payoutService.recordFailure(
                        fixture.settlementId(),
                        new CannotAcquireLockException("lock timeout"),
                        now);

                assertTrue(recorded);
                assertEquals(1, count(
                        jdbcTemplate,
                        "SELECT COUNT(*) FROM settlements"
                                + " WHERE id = ? AND status = 'FAILED' AND retry_count = 5"
                                + " AND next_retry_at IS NULL",
                        fixture.settlementId()));
            } finally {
                deleteFixture(jdbcTemplate, fixture);
            }
        }
    }

    @Test
    @Timeout(20)
    void recordFailureSkipsWhenIntegrityViolationRejectionOccursOnFirstAttempt() {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            SettlementScheduledPayoutService payoutService =
                    context.getBean(SettlementScheduledPayoutService.class);
            SettlementFixture fixture = createFixture(jdbcTemplate);
            LocalDateTime now = jdbcTemplate.queryForObject("SELECT NOW(6)", LocalDateTime.class);
            setDueAt(jdbcTemplate, fixture, now.minusSeconds(1));

            try {
                boolean recorded = payoutService.recordFailure(
                        fixture.settlementId(),
                        new SettlementPayoutRejectedException(
                                SettlementPayoutDecision.INTEGRITY_VIOLATION),
                        now);

                assertTrue(recorded);
                assertEquals(1, count(
                        jdbcTemplate,
                        "SELECT COUNT(*) FROM settlements"
                                + " WHERE id = ? AND status = 'FAILED'"
                                + " AND failure_code = 'SCHEDULER_INTEGRITY_VIOLATION'",
                        fixture.settlementId()));
            } finally {
                deleteFixture(jdbcTemplate, fixture);
            }
        }
    }

    @Test
    @Timeout(20)
    void recordFailureSkipsBenignAlreadyProcessedRejectionWithoutTouchingRetryCount() {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            SettlementScheduledPayoutService payoutService =
                    context.getBean(SettlementScheduledPayoutService.class);
            SettlementFixture fixture = createFixture(jdbcTemplate);
            LocalDateTime now = jdbcTemplate.queryForObject("SELECT NOW(6)", LocalDateTime.class);
            setDueAt(jdbcTemplate, fixture, now.minusSeconds(1));

            try {
                boolean recorded = payoutService.recordFailure(
                        fixture.settlementId(),
                        new SettlementPayoutRejectedException(
                                SettlementPayoutDecision.ALREADY_PROCESSED),
                        now);

                assertFalse(recorded);
                assertEquals(1, count(
                        jdbcTemplate,
                        "SELECT COUNT(*) FROM settlements"
                                + " WHERE id = ? AND status = 'SCHEDULED' AND retry_count = 0",
                        fixture.settlementId()));
            } finally {
                deleteFixture(jdbcTemplate, fixture);
            }
        }
    }

    @Test
    @Timeout(20)
    void stuckProcessingRowIsNeverPickedUpByCandidateQueryOrLock() {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            SettlementMapper settlementMapper = context.getBean(SettlementMapper.class);
            SettlementFixture fixture = createFixture(jdbcTemplate);
            LocalDateTime now = jdbcTemplate.queryForObject("SELECT NOW(6)", LocalDateTime.class);
            setDueAt(jdbcTemplate, fixture, now.minusHours(1));
            jdbcTemplate.update(
                    "UPDATE settlements SET status = 'PROCESSING', processing_at = NOW(6)"
                            + " WHERE id = ?",
                    fixture.settlementId());

            try {
                List<Long> candidates =
                        settlementMapper.findScheduledPayoutCandidateIds(now, 100);
                ScheduledPayoutCandidate locked =
                        settlementMapper.lockScheduledPayoutCandidate(
                                fixture.settlementId(), now);

                assertFalse(candidates.contains(fixture.settlementId()));
                assertNull(locked);
            } finally {
                jdbcTemplate.update(
                        "UPDATE settlements SET status = 'SCHEDULED', processing_at = NULL"
                                + " WHERE id = ?",
                        fixture.settlementId());
                deleteFixture(jdbcTemplate, fixture);
            }
        }
    }

    private AnnotationConfigApplicationContext applicationContext() {
        return new AnnotationConfigApplicationContext(RootConfig.class);
    }

    private JdbcTemplate jdbcTemplate(AnnotationConfigApplicationContext context) {
        return new JdbcTemplate(context.getBean(DataSource.class));
    }

    private void setDueAt(
            JdbcTemplate jdbcTemplate, SettlementFixture fixture, LocalDateTime dueAt) {
        jdbcTemplate.update(
                "UPDATE settlements SET due_at = ? WHERE id = ?",
                dueAt,
                fixture.settlementId());
    }

    private void openDispute(JdbcTemplate jdbcTemplate, SettlementFixture fixture) {
        jdbcTemplate.update(
                "INSERT INTO disputes"
                        + " (work_case_id, requester_id, dispute_type, title, content, status)"
                        + " VALUES (?, ?, 'WAGE', '지급 확인 요청', '통합 테스트 분쟁', 'OPEN')",
                fixture.workCaseId(),
                fixture.workerId());
    }

    private SettlementFixture createFixture(JdbcTemplate jdbcTemplate) {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String digits = UUID.randomUUID().toString().replaceAll("[^0-9]", "") + "0000000000";
        String businessNumber = digits.substring(0, 10);
        String ownerLogin = "it_sch_owner_" + token;
        String workerLogin = "it_sch_worker_" + token;

        jdbcTemplate.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'integration-test', '합성 고용주', 'OWNER', 'ACTIVE')",
                ownerLogin,
                ownerLogin + "@example.invalid");
        jdbcTemplate.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'integration-test', '합성 근로자', 'WORKER', 'ACTIVE')",
                workerLogin,
                workerLogin + "@example.invalid");
        Long employerId = idBy(jdbcTemplate, "users", "login_id", ownerLogin);
        Long workerId = idBy(jdbcTemplate, "users", "login_id", workerLogin);

        jdbcTemplate.update(
                "INSERT INTO wallets (user_id, currency, available_balance, locked_balance)"
                        + " VALUES (?, 'KRW', 0, ?)",
                employerId,
                WAGE);
        jdbcTemplate.update(
                "INSERT INTO wallets (user_id, currency, available_balance, locked_balance)"
                        + " VALUES (?, 'KRW', 0, 0)",
                workerId);
        Long employerWalletId = idBy(jdbcTemplate, "wallets", "user_id", employerId);
        Long workerWalletId = idBy(jdbcTemplate, "wallets", "user_id", workerId);

        insertWorkplace(jdbcTemplate, employerId, businessNumber);
        Long workplaceId = idBy(
                jdbcTemplate, "workplaces", "business_registration_number", businessNumber);

        jdbcTemplate.update(
                "INSERT INTO work_cases"
                        + " (employer_id, worker_id, workplace_id, title,"
                        + " starts_at, ends_at, break_minutes, break_paid,"
                        + " workplace_name, workplace_address,"
                        + " allowed_radius_meters, agreed_wage, terms_version, status)"
                        + " VALUES (?, ?, ?, '스케줄러 통합 테스트 근무',"
                        + " '2030-01-01 09:00:00', '2030-01-01 18:00:00', 60, 0,"
                        + " '통합 테스트 사업장', '서울특별시 테스트로 1',"
                        + " 100, ?, 1, 'COMPLETED')",
                employerId,
                workerId,
                workplaceId,
                WAGE);
        Long workCaseId = jdbcTemplate.queryForObject(
                "SELECT id FROM work_cases WHERE employer_id = ? AND title = '스케줄러 통합 테스트 근무'",
                Long.class,
                employerId);

        jdbcTemplate.update(
                "INSERT INTO escrows (work_case_id, amount, status, held_at)"
                        + " VALUES (?, ?, 'HELD', NOW(6))",
                workCaseId,
                WAGE);
        Long escrowId = idBy(jdbcTemplate, "escrows", "work_case_id", workCaseId);

        jdbcTemplate.update(
                "INSERT INTO settlements (work_case_id, amount, status, due_at)"
                        + " VALUES (?, ?, 'SCHEDULED', DATE_ADD(NOW(6), INTERVAL 1 DAY))",
                workCaseId,
                WAGE);
        Long settlementId = idBy(jdbcTemplate, "settlements", "work_case_id", workCaseId);

        jdbcTemplate.update(
                "INSERT INTO wallet_transactions"
                        + " (wallet_id, work_case_id, transaction_type, amount,"
                        + " available_before, available_after, locked_before,"
                        + " locked_after, reference_type, reference_id, idempotency_key)"
                        + " VALUES (?, ?, 'ESCROW_HOLD', ?, ?, 0, 0, ?, 'ESCROW', ?, ?)",
                employerWalletId,
                workCaseId,
                WAGE,
                WAGE,
                WAGE,
                escrowId,
                WalletIdempotencyKeys.escrowHold("IT-SCH-HOLD-" + token));

        return new SettlementFixture(
                employerId, workerId, workCaseId, escrowId, settlementId, workplaceId,
                employerWalletId, workerWalletId);
    }

    private void insertWorkplace(
            JdbcTemplate jdbcTemplate, Long employerId, String businessNumber) {
        int splitAddressColumnCount = count(
                jdbcTemplate,
                "SELECT COUNT(*) FROM information_schema.columns"
                        + " WHERE table_schema = DATABASE()"
                        + " AND table_name = 'workplaces' AND column_name = 'road_address'");
        if (splitAddressColumnCount > 0) {
            jdbcTemplate.update(
                    "INSERT INTO workplaces"
                            + " (owner_user_id, business_registration_number, name,"
                            + " representative_name, road_address, detail_address, phone, status)"
                            + " VALUES (?, ?, '통합 테스트 사업장', '합성 대표',"
                            + " '서울특별시 테스트로 1', NULL, '02-0000-0000', 'ACTIVE')",
                    employerId,
                    businessNumber);
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO workplaces"
                        + " (owner_user_id, business_registration_number, name,"
                        + " representative_name, address, phone, status)"
                        + " VALUES (?, ?, '통합 테스트 사업장', '합성 대표',"
                        + " '서울특별시 테스트로 1', '02-0000-0000', 'ACTIVE')",
                employerId,
                businessNumber);
    }

    private void deleteFixture(JdbcTemplate jdbcTemplate, SettlementFixture fixture) {
        jdbcTemplate.update(
                "DELETE FROM disputes WHERE work_case_id = ?", fixture.workCaseId());
        jdbcTemplate.update(
                "DELETE FROM wallet_transactions WHERE work_case_id = ?", fixture.workCaseId());
        jdbcTemplate.update(
                "DELETE FROM settlements WHERE work_case_id = ?", fixture.workCaseId());
        jdbcTemplate.update("DELETE FROM escrows WHERE id = ?", fixture.escrowId());
        jdbcTemplate.update("DELETE FROM work_cases WHERE id = ?", fixture.workCaseId());
        jdbcTemplate.update("DELETE FROM workplaces WHERE id = ?", fixture.workplaceId());
        jdbcTemplate.update(
                "DELETE FROM wallets WHERE id IN (?, ?)",
                fixture.employerWalletId(),
                fixture.workerWalletId());
        jdbcTemplate.update(
                "DELETE FROM users WHERE id IN (?, ?)",
                fixture.employerId(),
                fixture.workerId());
    }

    private Long idBy(JdbcTemplate jdbcTemplate, String table, String column, Object value) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM " + table + " WHERE " + column + " = ?", Long.class, value);
        if (id == null) {
            throw new IllegalStateException("통합 테스트 fixture ID를 찾을 수 없습니다.");
        }
        return id;
    }

    private int count(JdbcTemplate jdbcTemplate, String sql, Object... arguments) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        return count == null ? 0 : count;
    }

    private String text(JdbcTemplate jdbcTemplate, String sql, Object argument) {
        String value = jdbcTemplate.queryForObject(sql, String.class, argument);
        if (value == null) {
            throw new IllegalStateException("통합 테스트 상태를 찾을 수 없습니다.");
        }
        return value;
    }

    private Long nullableLong(JdbcTemplate jdbcTemplate, String sql, Object argument) {
        return jdbcTemplate.queryForObject(sql, Long.class, argument);
    }

    private record SettlementFixture(
            Long employerId,
            Long workerId,
            Long workCaseId,
            Long escrowId,
            Long settlementId,
            Long workplaceId,
            Long employerWalletId,
            Long workerWalletId) {
    }
}
