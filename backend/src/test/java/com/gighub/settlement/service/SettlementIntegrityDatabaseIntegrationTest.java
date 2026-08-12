package com.gighub.settlement.service;

import com.gighub.config.RootConfig;
import com.gighub.common.exception.ConflictException;
import com.gighub.member.domain.UserRole;
import com.gighub.settlement.exception.SettlementAlreadyProcessedException;
import com.gighub.settlement.exception.SettlementOnHoldException;
import com.gighub.settlement.service.command.SettlementApproveCommand;
import com.gighub.settlement.service.command.SettlementPayoutCommand;
import com.gighub.settlement.service.policy.SettlementPayoutDecision;
import com.gighub.settlement.service.policy.SettlementPayoutRejectedException;
import com.gighub.settlement.service.result.SettlementResult;
import com.gighub.wallet.exception.EscrowIntegrityException;
import com.gighub.wallet.idempotency.WalletIdempotencyKeys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("database")
class SettlementIntegrityDatabaseIntegrationTest {

    private static final Long WAGE = 300_000L;

    @Test
    @Timeout(20)
    void approveCompletesSettlementAndReplaysStoredResult() {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            SettlementService settlementService =
                    context.getBean(SettlementService.class);
            SettlementFixture fixture = createFixture(jdbcTemplate);
            SettlementApproveCommand command = command(
                    fixture,
                    fixture.approvalKey()
            );

            try {
                SettlementResult first = settlementService.approve(command);
                SettlementResult replay = settlementService.approve(command);

                assertEquals(fixture.settlementId(), first.getSettlementId());
                assertEquals("COMPLETED", first.getStatus());
                assertNotNull(first.getCompletedAt());
                assertFalse(first.isReplayed());
                assertFullPayoutAmounts(first);
                assertEquals(first.getSettlementId(), replay.getSettlementId());
                assertEquals(first.getCompletedAt(), replay.getCompletedAt());
                assertFullPayoutAmounts(replay);
                assertTrue(replay.isReplayed());
                assertCompletedState(jdbcTemplate, fixture, first.getCompletedAt());
            } finally {
                deleteFixture(jdbcTemplate, fixture);
            }
        }
    }

    @Test
    @Timeout(20)
    void ownerApprovalClearsScheduledRetryTimeBeforeProcessing() {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            SettlementService settlementService = context.getBean(SettlementService.class);
            SettlementFixture fixture = createFixture(jdbcTemplate);

            try {
                jdbcTemplate.update(
                        "UPDATE settlements SET retry_count = 1,"
                                + " failure_code = 'TEMPORARY_PAYOUT_FAILURE',"
                                + " last_failure_at = NOW(6),"
                                + " next_retry_at = DATE_ADD(NOW(6), INTERVAL 10 MINUTE)"
                                + " WHERE id = ?",
                        fixture.settlementId());

                // 재시도 대기 중이어도 OWNER는 즉시 승인할 수 있다. PROCESSING 선점과 동시에
                // 다음 자동 재시도 시각을 지워 신규 lifecycle CHECK와 실제 지급 경로를 맞춘다.
                SettlementResult result = settlementService.approve(command(
                        fixture,
                        fixture.approvalKey()));

                assertEquals("COMPLETED", result.getStatus());
                assertEquals(0, count(
                        jdbcTemplate,
                        "SELECT COUNT(*) FROM settlements"
                                + " WHERE id = ? AND next_retry_at IS NOT NULL",
                        fixture.settlementId()));
            } finally {
                deleteFixture(jdbcTemplate, fixture);
            }
        }
    }

    @Test
    @Timeout(25)
    void concurrentSameKeyPaysOnlyOnceAndThenConflictsOrReplays() throws Exception {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            SettlementService settlementService =
                    context.getBean(SettlementService.class);
            SettlementFixture fixture = createFixture(jdbcTemplate);
            SettlementApproveCommand command = command(
                    fixture,
                    fixture.approvalKey()
            );
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            Callable<SettlementResult> request = () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "동시 정산 테스트 시작 신호를 기다리지 못했습니다."
                    );
                }
                return settlementService.approve(command);
            };

            try {
                Future<SettlementResult> firstFuture = executor.submit(request);
                Future<SettlementResult> secondFuture = executor.submit(request);
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();

                int firstSuccesses = 0;
                int replayOrInProgress = 0;
                SettlementResult completed = null;
                for (Future<SettlementResult> future : List.of(firstFuture, secondFuture)) {
                    try {
                        SettlementResult result = future.get(10, TimeUnit.SECONDS);
                        if (result.isReplayed()) {
                            replayOrInProgress++;
                        } else {
                            firstSuccesses++;
                            completed = result;
                        }
                    } catch (ExecutionException executionException) {
                        assertTrue(executionException.getCause() instanceof ConflictException);
                        replayOrInProgress++;
                    }
                }

                assertEquals(1, firstSuccesses);
                assertEquals(1, replayOrInProgress);
                assertNotNull(completed);
                assertFullPayoutAmounts(completed);
                SettlementResult replay = settlementService.approve(command);
                assertTrue(replay.isReplayed());
                assertEquals(completed.getCompletedAt(), replay.getCompletedAt());
                assertFullPayoutAmounts(replay);
                assertCompletedState(jdbcTemplate, fixture, completed.getCompletedAt());
            } finally {
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
                deleteFixture(jdbcTemplate, fixture);
            }
        }
    }

    @Test
    @Timeout(25)
    void concurrentDifferentKeysAllowOnlyOnePayout() throws Exception {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            SettlementService settlementService =
                    context.getBean(SettlementService.class);
            SettlementFixture fixture = createFixture(jdbcTemplate);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);

            Callable<SettlementResult> firstRequest = concurrentRequest(
                    settlementService,
                    command(fixture, fixture.approvalKey() + "-A"),
                    ready,
                    start
            );
            Callable<SettlementResult> secondRequest = concurrentRequest(
                    settlementService,
                    command(fixture, fixture.approvalKey() + "-B"),
                    ready,
                    start
            );

            try {
                List<Future<SettlementResult>> futures = List.of(
                        executor.submit(firstRequest),
                        executor.submit(secondRequest)
                );
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();

                int successes = 0;
                int conflicts = 0;
                SettlementResult completed = null;
                for (Future<SettlementResult> future : futures) {
                    try {
                        completed = future.get(10, TimeUnit.SECONDS);
                        successes++;
                    } catch (ExecutionException executionException) {
                        assertTrue(
                                executionException.getCause()
                                        instanceof SettlementAlreadyProcessedException
                        );
                        conflicts++;
                    }
                }

                assertEquals(1, successes);
                assertEquals(1, conflicts);
                assertNotNull(completed);
                assertFullPayoutAmounts(completed);
                assertCompletedState(
                        jdbcTemplate,
                        fixture,
                        completed.getCompletedAt()
                );
            } finally {
                start.countDown();
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
                deleteFixture(jdbcTemplate, fixture);
            }
        }
    }

    @Test
    @Timeout(30)
    void manualAndScheduledPayoutRaceHasOneWinnerAndPreservesNullableApprover()
            throws Exception {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            SettlementService settlementService = context.getBean(SettlementService.class);
            SettlementPayoutExecutor payoutExecutor =
                    context.getBean(SettlementPayoutExecutor.class);
            TransactionTemplate scheduledTransaction = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class));
            scheduledTransaction.setPropagationBehavior(
                    TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            SettlementFixture fixture = createFixture(jdbcTemplate);
            jdbcTemplate.update(
                    "UPDATE settlements SET due_at = DATE_SUB(NOW(6), INTERVAL 1 SECOND)"
                            + " WHERE id = ?",
                    fixture.settlementId());
            LocalDateTime eligibilityTime = jdbcTemplate.queryForObject(
                    "SELECT NOW(6)", LocalDateTime.class);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);

            Callable<SettlementResult> ownerRequest = () -> {
                awaitRaceStart(ready, start);
                return settlementService.approve(command(
                        fixture, fixture.approvalKey() + "-MANUAL"));
            };
            Callable<SettlementResult> scheduledRequest = () -> {
                awaitRaceStart(ready, start);
                return scheduledTransaction.execute(status -> payoutExecutor.execute(
                        SettlementPayoutCommand.scheduled(
                                fixture.settlementId(),
                                fixture.workCaseId(),
                                eligibilityTime)));
            };

            try {
                Future<SettlementResult> ownerFuture = executor.submit(ownerRequest);
                Future<SettlementResult> scheduledFuture = executor.submit(scheduledRequest);
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();

                boolean ownerSucceeded = completesSuccessfully(ownerFuture);
                boolean scheduledSucceeded = completesSuccessfully(scheduledFuture);
                assertTrue(ownerSucceeded ^ scheduledSucceeded);
                assertPayoutState(
                        jdbcTemplate,
                        fixture,
                        ownerSucceeded ? fixture.employerId() : null);
            } finally {
                start.countDown();
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
                deleteFixture(jdbcTemplate, fixture);
            }
        }
    }

    @Test
    @Timeout(25)
    void completedClaimReplaysTheCommittedResponse() throws Exception {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            SettlementService settlementService =
                    context.getBean(SettlementService.class);
            SettlementFixture fixture = createFixture(jdbcTemplate);
            ExecutorService executor = Executors.newSingleThreadExecutor();

            try {
                Future<SettlementResult> first = executor.submit(
                        () -> settlementService.approve(command(
                                fixture,
                                fixture.approvalKey())));
                SettlementResult completed = first.get(10, TimeUnit.SECONDS);
                SettlementResult replay = settlementService.approve(command(
                        fixture,
                        fixture.approvalKey()));

                assertNotNull(replay);
                assertTrue(replay.isReplayed());
                assertEquals(completed.getCompletedAt(), replay.getCompletedAt());
                assertFullPayoutAmounts(completed);
                assertFullPayoutAmounts(replay);
                assertCompletedState(
                        jdbcTemplate,
                        fixture,
                        replay.getCompletedAt()
                );
            } finally {
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
                deleteFixture(jdbcTemplate, fixture);
            }
        }
    }

    @Test
    @Timeout(20)
    void deterministicLedgerCollisionRollsBackEverySettlementMutation() {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            SettlementService settlementService =
                    context.getBean(SettlementService.class);
            SettlementFixture fixture = createFixture(jdbcTemplate);

            try {
                jdbcTemplate.update(
                        "INSERT INTO wallet_transactions"
                                + " (wallet_id, work_case_id, transaction_type, amount,"
                                + " available_before, available_after, locked_before,"
                                + " locked_after, reference_type, reference_id,"
                                + " idempotency_key)"
                                + " VALUES (?, ?, 'ESCROW_RELEASE', ?, 0, 0, ?, 0,"
                                + " 'ESCROW', ?, ?)",
                        fixture.employerWalletId(),
                        fixture.workCaseId(),
                        WAGE,
                        WAGE,
                        fixture.escrowId(),
                        WalletIdempotencyKeys.settlementReleaseOwner(
                                fixture.settlementId()));
                assertThrows(
                        EscrowIntegrityException.class,
                        () -> settlementService.approve(command(
                                fixture,
                                fixture.approvalKey()))
                );

                jdbcTemplate.update(
                        "DELETE FROM wallet_transactions WHERE idempotency_key = ?",
                        WalletIdempotencyKeys.settlementReleaseOwner(
                                fixture.settlementId()));
                assertInitialState(jdbcTemplate, fixture);
                assertEquals(0, count(
                        jdbcTemplate,
                        "SELECT COUNT(*) FROM idempotency_requests"
                                + " WHERE user_id = ?"
                                + " AND operation_code = 'SETTLEMENT_APPROVE'",
                        fixture.employerId()));
            } finally {
                deleteFixture(jdbcTemplate, fixture);
            }
        }
    }

    @Test
    @Timeout(20)
    void secondLedgerCollisionRollsBackTheFirstLedgerAndEveryMoneyMutation() {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            SettlementService settlementService = context.getBean(SettlementService.class);
            SettlementFixture fixture = createFixture(jdbcTemplate);
            String workerLedgerKey = WalletIdempotencyKeys.settlementReleaseWorker(
                    fixture.settlementId());

            try {
                jdbcTemplate.update(
                        "INSERT INTO wallet_transactions"
                                + " (wallet_id, work_case_id, transaction_type, amount,"
                                + " available_before, available_after, locked_before,"
                                + " locked_after, reference_type, reference_id,"
                                + " idempotency_key)"
                                + " VALUES (?, ?, 'ESCROW_RELEASE', ?, 0, ?, 0, 0,"
                                + " 'ESCROW', ?, ?)",
                        fixture.workerWalletId(),
                        fixture.workCaseId(),
                        WAGE,
                        WAGE,
                        fixture.escrowId(),
                        workerLedgerKey);

                assertThrows(
                        EscrowIntegrityException.class,
                        () -> settlementService.approve(command(
                                fixture, fixture.approvalKey())));

                jdbcTemplate.update(
                        "DELETE FROM wallet_transactions WHERE idempotency_key = ?",
                        workerLedgerKey);
                assertInitialState(jdbcTemplate, fixture);
                assertEquals(0, count(
                        jdbcTemplate,
                        "SELECT COUNT(*) FROM idempotency_requests"
                                + " WHERE user_id = ?"
                                + " AND operation_code = 'SETTLEMENT_APPROVE'",
                        fixture.employerId()));
            } finally {
                deleteFixture(jdbcTemplate, fixture);
            }
        }
    }

    @Test
    @Timeout(20)
    void claimCompletionFailureRollsBackCompletedPayoutAndBothLedgers() {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            SettlementApprovalTransaction approvalTransaction =
                    context.getBean(SettlementApprovalTransaction.class);
            SettlementFixture fixture = createFixture(jdbcTemplate);

            try {
                assertThrows(
                        RuntimeException.class,
                        () -> approvalTransaction.execute(
                                command(fixture, fixture.approvalKey()),
                                Long.MAX_VALUE));

                assertInitialState(jdbcTemplate, fixture);
                assertEquals(0, count(
                        jdbcTemplate,
                        "SELECT COUNT(*) FROM idempotency_requests"
                                + " WHERE id = ?",
                        Long.MAX_VALUE));
            } finally {
                deleteFixture(jdbcTemplate, fixture);
            }
        }
    }

    @Test
    @Timeout(20)
    void missingOrBlockedSettlementNeverMovesFunds() {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            SettlementService settlementService =
                    context.getBean(SettlementService.class);
            SettlementFixture missingFixture = createFixture(jdbcTemplate);

            try {
                jdbcTemplate.update(
                        "DELETE FROM settlements WHERE id = ?",
                        missingFixture.settlementId()
                );
                assertThrows(
                        EscrowIntegrityException.class,
                        () -> settlementService.approve(command(
                                missingFixture,
                                missingFixture.approvalKey()
                        ))
                );
                assertFundsUnchanged(jdbcTemplate, missingFixture);
            } finally {
                deleteFixture(jdbcTemplate, missingFixture);
            }

            SettlementFixture blockedFixture = createFixture(jdbcTemplate);
            try {
                jdbcTemplate.update(
                        "UPDATE settlements SET status = 'ON_HOLD' WHERE id = ?",
                        blockedFixture.settlementId()
                );
                assertThrows(
                        SettlementOnHoldException.class,
                        () -> settlementService.approve(command(
                                blockedFixture,
                                blockedFixture.approvalKey()
                        ))
                );
                assertFundsUnchanged(jdbcTemplate, blockedFixture);
                assertEquals(
                        "ON_HOLD",
                        text(
                                jdbcTemplate,
                                "SELECT status FROM settlements WHERE id = ?",
                                blockedFixture.settlementId()
                        )
                );
            } finally {
                deleteFixture(jdbcTemplate, blockedFixture);
            }
        }
    }

    @Test
    @Timeout(20)
    void openDisputeBlocksManualPayoutWithoutChangingMoney() {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            SettlementService settlementService = context.getBean(SettlementService.class);
            SettlementFixture fixture = createFixture(jdbcTemplate);

            try {
                jdbcTemplate.update(
                        "INSERT INTO disputes"
                                + " (work_case_id, requester_id, dispute_type,"
                                + " title, content, status)"
                                + " VALUES (?, ?, 'WAGE', '지급 확인 요청',"
                                + " '통합 테스트 분쟁', 'OPEN')",
                        fixture.workCaseId(),
                        fixture.workerId());

                assertThrows(
                        SettlementOnHoldException.class,
                        () -> settlementService.approve(command(
                                fixture,
                                fixture.approvalKey())));

                assertFundsUnchanged(jdbcTemplate, fixture);
                assertEquals("SCHEDULED", text(
                        jdbcTemplate,
                        "SELECT status FROM settlements WHERE id = ?",
                        fixture.settlementId()));
                assertEquals(0, count(
                        jdbcTemplate,
                        "SELECT COUNT(*) FROM idempotency_requests"
                                + " WHERE user_id = ?"
                                + " AND operation_code = 'SETTLEMENT_APPROVE'",
                        fixture.employerId()));
            } finally {
                deleteFixture(jdbcTemplate, fixture);
            }
        }
    }

    private AnnotationConfigApplicationContext applicationContext() {
        return new AnnotationConfigApplicationContext(RootConfig.class);
    }

    private JdbcTemplate jdbcTemplate(
            AnnotationConfigApplicationContext context) {
        return new JdbcTemplate(context.getBean(DataSource.class));
    }

    private SettlementApproveCommand command(
            SettlementFixture fixture,
            String idempotencyKey) {
        return SettlementApproveCommand.builder()
                .workCaseId(fixture.workCaseId())
                .approverUserId(fixture.employerId())
                .approverRole(UserRole.OWNER)
                .idempotencyKey(idempotencyKey)
                .build();
    }

    private Callable<SettlementResult> concurrentRequest(
            SettlementService settlementService,
            SettlementApproveCommand command,
            CountDownLatch ready,
            CountDownLatch start) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "동시 정산 테스트 시작 신호를 기다리지 못했습니다."
                );
            }
            return settlementService.approve(command);
        };
    }

    private void awaitRaceStart(CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("동시 정산 테스트 시작 신호를 기다리지 못했습니다.");
        }
    }

    private boolean completesSuccessfully(Future<SettlementResult> future)
            throws Exception {
        try {
            SettlementResult result = future.get(15, TimeUnit.SECONDS);
            assertFullPayoutAmounts(result);
            return true;
        } catch (ExecutionException executionException) {
            Throwable cause = executionException.getCause();
            if (cause instanceof SettlementPayoutRejectedException rejected) {
                assertEquals(
                        SettlementPayoutDecision.ALREADY_PROCESSED,
                        rejected.getDecision());
            } else {
                assertTrue(cause instanceof SettlementAlreadyProcessedException);
            }
            return false;
        }
    }

    private void assertCompletedState(
            JdbcTemplate jdbcTemplate,
            SettlementFixture fixture,
            LocalDateTime completedAt) {
        assertCompletedState(
                jdbcTemplate, fixture, completedAt, fixture.employerId());
    }

    private void assertPayoutState(
            JdbcTemplate jdbcTemplate,
            SettlementFixture fixture,
            Long approvedByUserId) {
        LocalDateTime completedAt = dateTime(
                jdbcTemplate,
                "SELECT completed_at FROM settlements WHERE id = ?",
                fixture.settlementId());
        assertNotNull(completedAt);
        assertCompletedState(
                jdbcTemplate, fixture, completedAt, approvedByUserId);
    }

    private void assertCompletedState(
            JdbcTemplate jdbcTemplate,
            SettlementFixture fixture,
            LocalDateTime completedAt,
            Long approvedByUserId) {
        assertEquals(0L, value(
                jdbcTemplate,
                "SELECT locked_balance FROM wallets WHERE id = ?",
                fixture.employerWalletId()
        ));
        assertEquals(0L, value(
                jdbcTemplate,
                "SELECT available_balance FROM wallets WHERE id = ?",
                fixture.employerWalletId()
        ));
        assertEquals(WAGE.longValue(), value(
                jdbcTemplate,
                "SELECT available_balance FROM wallets WHERE id = ?",
                fixture.workerWalletId()
        ));
        assertEquals(0L, value(
                jdbcTemplate,
                "SELECT locked_balance FROM wallets WHERE id = ?",
                fixture.workerWalletId()
        ));
        assertEquals("RELEASED", text(
                jdbcTemplate,
                "SELECT status FROM escrows WHERE id = ?",
                fixture.escrowId()
        ));
        assertEquals("COMPLETED", text(
                jdbcTemplate,
                "SELECT status FROM work_cases WHERE id = ?",
                fixture.workCaseId()
        ));
        assertEquals("COMPLETED", text(
                jdbcTemplate,
                "SELECT status FROM settlements WHERE id = ?",
                fixture.settlementId()
        ));
        assertEquals(approvedByUserId, nullableLong(
                jdbcTemplate,
                "SELECT approved_by_user_id FROM settlements WHERE id = ?",
                fixture.settlementId()
        ));
        assertNotNull(dateTime(
                jdbcTemplate,
                "SELECT processing_at FROM settlements WHERE id = ?",
                fixture.settlementId()
        ));
        assertEquals(completedAt, dateTime(
                jdbcTemplate,
                "SELECT completed_at FROM settlements WHERE id = ?",
                fixture.settlementId()
        ));
        assertEquals(3, count(
                jdbcTemplate,
                "SELECT COUNT(*) FROM wallet_transactions"
                        + " WHERE work_case_id = ?",
                fixture.workCaseId()
        ));
        assertEquals(2, count(
                jdbcTemplate,
                "SELECT COUNT(*) FROM wallet_transactions"
                        + " WHERE work_case_id = ?"
                        + " AND transaction_type = 'ESCROW_RELEASE'"
                        + " AND reference_type = 'ESCROW'"
                        + " AND reference_id = ?",
                fixture.workCaseId(),
                fixture.escrowId()
        ));
        assertEquals(1, count(
                jdbcTemplate,
                "SELECT COUNT(*)"
                        + " FROM wallet_transactions wt"
                        + " JOIN wallets w ON w.id = wt.wallet_id"
                        + " WHERE wt.work_case_id = ?"
                        + " AND w.user_id = ?"
                        + " AND wt.transaction_type = 'ESCROW_RELEASE'"
                        + " AND wt.available_before = 0"
                        + " AND wt.available_after = 0"
                        + " AND wt.locked_before = ?"
                        + " AND wt.locked_after = 0"
                        + " AND wt.idempotency_key LIKE 'SETTLEMENT_RELEASE_OWNER:%'",
                fixture.workCaseId(),
                fixture.employerId(),
                WAGE
        ));
        assertEquals(1, count(
                jdbcTemplate,
                "SELECT COUNT(*)"
                        + " FROM wallet_transactions wt"
                        + " JOIN wallets w ON w.id = wt.wallet_id"
                        + " WHERE wt.work_case_id = ?"
                        + " AND w.user_id = ?"
                        + " AND wt.transaction_type = 'ESCROW_RELEASE'"
                        + " AND wt.available_before = 0"
                        + " AND wt.available_after = ?"
                        + " AND wt.locked_before = 0"
                        + " AND wt.locked_after = 0"
                        + " AND wt.idempotency_key LIKE 'SETTLEMENT_RELEASE_WORKER:%'",
                fixture.workCaseId(),
                fixture.workerId(),
                WAGE
        ));
        assertEquals(WAGE.longValue(), value(
                jdbcTemplate,
                "SELECT SUM(available_balance + locked_balance)"
                        + " FROM wallets WHERE id IN (?, ?)",
                fixture.employerWalletId(),
                fixture.workerWalletId()
        ));
    }

    private void assertFullPayoutAmounts(SettlementResult result) {
        assertEquals(WAGE, result.getSettlementAmount());
        assertEquals(WAGE, result.getOriginalEscrowAmount());
        assertEquals(WAGE, result.getWorkerPaidAmount());
        assertEquals(0L, result.getOwnerRefundAmount());
        assertEquals(
                result.getOriginalEscrowAmount().longValue(),
                Math.addExact(
                        result.getWorkerPaidAmount(),
                        result.getOwnerRefundAmount()));
    }

    private void assertInitialState(
            JdbcTemplate jdbcTemplate,
            SettlementFixture fixture) {
        assertFundsUnchanged(jdbcTemplate, fixture);
        assertEquals("SCHEDULED", text(
                jdbcTemplate,
                "SELECT status FROM settlements WHERE id = ?",
                fixture.settlementId()
        ));
        assertEquals(0, count(
                jdbcTemplate,
                "SELECT COUNT(*) FROM settlements"
                        + " WHERE id = ? AND approved_by_user_id IS NOT NULL",
                fixture.settlementId()
        ));
        assertNotNull(dateTime(
                jdbcTemplate,
                "SELECT due_at FROM settlements WHERE id = ?",
                fixture.settlementId()
        ));
        assertEquals(0, count(
                jdbcTemplate,
                "SELECT COUNT(*) FROM settlements"
                        + " WHERE id = ?"
                        + " AND (processing_at IS NOT NULL OR completed_at IS NOT NULL)",
                fixture.settlementId()
        ));
    }

    private void assertFundsUnchanged(
            JdbcTemplate jdbcTemplate,
            SettlementFixture fixture) {
        assertEquals(WAGE.longValue(), value(
                jdbcTemplate,
                "SELECT locked_balance FROM wallets WHERE id = ?",
                fixture.employerWalletId()
        ));
        assertEquals(0L, value(
                jdbcTemplate,
                "SELECT available_balance FROM wallets WHERE id = ?",
                fixture.workerWalletId()
        ));
        assertEquals("HELD", text(
                jdbcTemplate,
                "SELECT status FROM escrows WHERE id = ?",
                fixture.escrowId()
        ));
        assertEquals("COMPLETED", text(
                jdbcTemplate,
                "SELECT status FROM work_cases WHERE id = ?",
                fixture.workCaseId()
        ));
        assertEquals(1, count(
                jdbcTemplate,
                "SELECT COUNT(*) FROM wallet_transactions"
                        + " WHERE work_case_id = ?",
                fixture.workCaseId()
        ));
    }

    private SettlementFixture createFixture(JdbcTemplate jdbcTemplate) {
        String token =
                UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String digits =
                UUID.randomUUID().toString().replaceAll("[^0-9]", "")
                        + "0000000000";
        String businessNumber = digits.substring(0, 10);
        String ownerLogin = "it_settle_owner_" + token;
        String workerLogin = "it_settle_worker_" + token;

        jdbcTemplate.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'integration-test',"
                        + " '합성 고용주', 'OWNER', 'ACTIVE')",
                ownerLogin,
                ownerLogin + "@example.invalid"
        );
        jdbcTemplate.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'integration-test',"
                        + " '합성 근로자', 'WORKER', 'ACTIVE')",
                workerLogin,
                workerLogin + "@example.invalid"
        );
        Long employerId =
                idBy(jdbcTemplate, "users", "login_id", ownerLogin);
        Long workerId =
                idBy(jdbcTemplate, "users", "login_id", workerLogin);

        jdbcTemplate.update(
                "INSERT INTO wallets"
                        + " (user_id, currency, available_balance, locked_balance)"
                        + " VALUES (?, 'KRW', 0, ?)",
                employerId,
                WAGE
        );
        jdbcTemplate.update(
                "INSERT INTO wallets"
                        + " (user_id, currency, available_balance, locked_balance)"
                        + " VALUES (?, 'KRW', 0, 0)",
                workerId
        );
        Long employerWalletId =
                idBy(jdbcTemplate, "wallets", "user_id", employerId);
        Long workerWalletId =
                idBy(jdbcTemplate, "wallets", "user_id", workerId);

        insertWorkplace(jdbcTemplate, employerId, businessNumber);
        Long workplaceId = idBy(
                jdbcTemplate,
                "workplaces",
                "business_registration_number",
                businessNumber
        );

        jdbcTemplate.update(
                "INSERT INTO work_cases"
                        + " (employer_id, worker_id, workplace_id, title,"
                        + " starts_at, ends_at, break_minutes, break_paid,"
                        + " workplace_name, workplace_address,"
                        + " allowed_radius_meters, agreed_wage,"
                        + " terms_version, status)"
                        + " VALUES (?, ?, ?, '통합 테스트 근무',"
                        + " '2030-01-01 09:00:00',"
                        + " '2030-01-01 18:00:00', 60, 0,"
                        + " '통합 테스트 사업장', '서울특별시 테스트로 1',"
                        + " 100, ?, 1, 'COMPLETED')",
                employerId,
                workerId,
                workplaceId,
                WAGE
        );
        Long workCaseId = jdbcTemplate.queryForObject(
                "SELECT id FROM work_cases"
                        + " WHERE employer_id = ?"
                        + " AND title = '통합 테스트 근무'",
                Long.class,
                employerId
        );

        jdbcTemplate.update(
                "INSERT INTO escrows"
                        + " (work_case_id, amount, status, held_at)"
                        + " VALUES (?, ?, 'HELD', NOW(6))",
                workCaseId,
                WAGE
        );
        Long escrowId =
                idBy(jdbcTemplate, "escrows", "work_case_id", workCaseId);

        jdbcTemplate.update(
                "INSERT INTO settlements (work_case_id, amount, status, due_at)"
                        + " VALUES (?, ?, 'SCHEDULED', DATE_ADD(NOW(6), INTERVAL 1 DAY))",
                workCaseId,
                WAGE
        );
        Long settlementId =
                idBy(jdbcTemplate, "settlements", "work_case_id", workCaseId);

        jdbcTemplate.update(
                "INSERT INTO wallet_transactions"
                        + " (wallet_id, work_case_id, transaction_type, amount,"
                        + " available_before, available_after, locked_before,"
                        + " locked_after, reference_type, reference_id,"
                        + " idempotency_key)"
                        + " VALUES (?, ?, 'ESCROW_HOLD', ?, ?, 0, 0, ?,"
                        + " 'ESCROW', ?, ?)",
                employerWalletId,
                workCaseId,
                WAGE,
                WAGE,
                WAGE,
                escrowId,
                WalletIdempotencyKeys.escrowHold(
                        "IT-SETTLE-HOLD-" + token
                )
        );

        return new SettlementFixture(
                employerId,
                workerId,
                employerWalletId,
                workerWalletId,
                workplaceId,
                workCaseId,
                escrowId,
                settlementId,
                "IT-SETTLE-APPROVE-" + token
        );
    }

    private void insertWorkplace(
            JdbcTemplate jdbcTemplate,
            Long employerId,
            String businessNumber) {
        // 주소 분리 마이그레이션 적용 여부를 확인해 구·신 스키마에서 같은 fixture를 사용한다.
        int splitAddressColumnCount = count(
                jdbcTemplate,
                "SELECT COUNT(*) FROM information_schema.columns"
                        + " WHERE table_schema = DATABASE()"
                        + " AND table_name = 'workplaces'"
                        + " AND column_name = 'road_address'"
        );
        if (splitAddressColumnCount > 0) {
            jdbcTemplate.update(
                    "INSERT INTO workplaces"
                            + " (owner_user_id,"
                            + " business_registration_number, name,"
                            + " representative_name, road_address,"
                            + " detail_address, phone, status)"
                            + " VALUES (?, ?, '통합 테스트 사업장',"
                            + " '합성 대표', '서울특별시 테스트로 1',"
                            + " NULL, '02-0000-0000', 'ACTIVE')",
                    employerId,
                    businessNumber
            );
            return;
        }

        jdbcTemplate.update(
                "INSERT INTO workplaces"
                        + " (owner_user_id, business_registration_number, name,"
                        + " representative_name, address, phone, status)"
                        + " VALUES (?, ?, '통합 테스트 사업장', '합성 대표',"
                        + " '서울특별시 테스트로 1', '02-0000-0000', 'ACTIVE')",
                employerId,
                businessNumber
        );
    }

    private void deleteFixture(
            JdbcTemplate jdbcTemplate,
            SettlementFixture fixture) {
        jdbcTemplate.update(
                "DELETE FROM idempotency_requests WHERE user_id IN (?, ?)",
                fixture.employerId(),
                fixture.workerId()
        );
        jdbcTemplate.update(
                "DELETE FROM disputes WHERE work_case_id = ?",
                fixture.workCaseId()
        );
        jdbcTemplate.update(
                "DELETE FROM wallet_transactions WHERE work_case_id = ?",
                fixture.workCaseId()
        );
        jdbcTemplate.update(
                "DELETE FROM settlements WHERE work_case_id = ?",
                fixture.workCaseId()
        );
        jdbcTemplate.update(
                "DELETE FROM escrows WHERE id = ?",
                fixture.escrowId()
        );
        jdbcTemplate.update(
                "DELETE FROM work_cases WHERE id = ?",
                fixture.workCaseId()
        );
        jdbcTemplate.update(
                "DELETE FROM workplaces WHERE id = ?",
                fixture.workplaceId()
        );
        jdbcTemplate.update(
                "DELETE FROM wallets WHERE id IN (?, ?)",
                fixture.employerWalletId(),
                fixture.workerWalletId()
        );
        jdbcTemplate.update(
                "DELETE FROM users WHERE id IN (?, ?)",
                fixture.employerId(),
                fixture.workerId()
        );
    }

    private Long idBy(
            JdbcTemplate jdbcTemplate,
            String table,
            String column,
            Object value) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM " + table + " WHERE " + column + " = ?",
                Long.class,
                value
        );
        if (id == null) {
            throw new IllegalStateException(
                    "통합 테스트 fixture ID를 찾을 수 없습니다."
            );
        }
        return id;
    }

    private int count(
            JdbcTemplate jdbcTemplate,
            String sql,
            Object... arguments) {
        Integer count =
                jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        return count == null ? 0 : count;
    }

    private long value(
            JdbcTemplate jdbcTemplate,
            String sql,
            Object... arguments) {
        Long value = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                arguments
        );
        if (value == null) {
            throw new IllegalStateException(
                    "통합 테스트 숫자 값을 찾을 수 없습니다."
            );
        }
        return value;
    }

    private Long nullableLong(
            JdbcTemplate jdbcTemplate,
            String sql,
            Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Long.class, arguments);
    }

    private String text(
            JdbcTemplate jdbcTemplate,
            String sql,
            Object argument) {
        String value =
                jdbcTemplate.queryForObject(sql, String.class, argument);
        if (value == null) {
            throw new IllegalStateException(
                    "통합 테스트 상태를 찾을 수 없습니다."
            );
        }
        return value;
    }

    private LocalDateTime dateTime(
            JdbcTemplate jdbcTemplate,
            String sql,
            Object argument) {
        return jdbcTemplate.queryForObject(
                sql,
                LocalDateTime.class,
                argument
        );
    }

    private record SettlementFixture(
            Long employerId,
            Long workerId,
            Long employerWalletId,
            Long workerWalletId,
            Long workplaceId,
            Long workCaseId,
            Long escrowId,
            Long settlementId,
            String approvalKey) {
    }
}
