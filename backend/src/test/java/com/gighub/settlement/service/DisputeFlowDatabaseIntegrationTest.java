package com.gighub.settlement.service;

import com.gighub.common.api.PageResponse;
import com.gighub.config.RootConfig;
import com.gighub.member.domain.UserRole;
import com.gighub.settlement.domain.DisputeStatus;
import com.gighub.settlement.dto.DisputeListItemResponse;
import com.gighub.settlement.exception.DisputeAlreadyOpenException;
import com.gighub.settlement.exception.SettlementOnHoldException;
import com.gighub.settlement.mapper.DisputeReviewMapper;
import com.gighub.settlement.mapper.result.DisputeReviewCandidate;
import com.gighub.settlement.review.DisputeReviewDecision;
import com.gighub.settlement.review.DisputeReviewExecution;
import com.gighub.settlement.review.DisputeReviewProviderResult;
import com.gighub.settlement.review.DisputeReviewResult;
import com.gighub.settlement.service.command.NoShowRefundApproveCommand;
import com.gighub.settlement.service.command.DisputeCreateCommand;
import com.gighub.settlement.service.command.SettlementApproveCommand;
import com.gighub.settlement.service.result.SettlementResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.support.TestPropertySourceUtils;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.math.BigDecimal;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실제 MySQL에서 동시 신고와 정산·자금 보존을 검증합니다. */
@Tag("database")
class DisputeFlowDatabaseIntegrationTest {

    private static final long WAGE = 120_000L;

    @Test
    @Timeout(25)
    void concurrentReportsCreateOneOpenDisputeAndPreserveEveryMoneySnapshot() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            DisputeService service = context.getBean(DisputeService.class);
            Fixture fixture = createFixture(jdbc);
            LocalDateTime dueAt = jdbc.queryForObject(
                    "SELECT due_at FROM settlements WHERE work_case_id = ?",
                    LocalDateTime.class,
                    fixture.workCaseId()
            );
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            Callable<String> report = () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try {
                    service.create(command(fixture));
                    return "CREATED";
                } catch (DisputeAlreadyOpenException duplicate) {
                    return "DUPLICATE";
                }
            };

            try {
                Future<String> first = executor.submit(report);
                Future<String> second = executor.submit(report);
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                List<String> outcomes = List.of(
                        first.get(15, TimeUnit.SECONDS),
                        second.get(15, TimeUnit.SECONDS)
                );

                assertEquals(1, outcomes.stream().filter("CREATED"::equals).count());
                assertEquals(1, outcomes.stream().filter("DUPLICATE"::equals).count());
                assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM disputes WHERE work_case_id = ?",
                        fixture.workCaseId()));
                assertEquals("ON_HOLD", text(jdbc,
                        "SELECT status FROM settlements WHERE work_case_id = ?",
                        fixture.workCaseId()));
                assertEquals(dueAt, jdbc.queryForObject(
                        "SELECT due_at FROM settlements WHERE work_case_id = ?",
                        LocalDateTime.class,
                        fixture.workCaseId()));
                assertMoneyUnchanged(jdbc, fixture);

                PageResponse<DisputeListItemResponse> page = service.findPage(
                        fixture.workCaseId(), fixture.ownerId(), UserRole.OWNER, 0, 20);
                assertEquals(1, page.getContent().size());
                assertEquals(UserRole.WORKER, page.getContent().get(0).getRequesterRole());
            } finally {
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
                deleteFixture(jdbc, fixture);
            }
        }
    }

    @Test
    @Timeout(25)
    void payoutAndDisputeRaceEndsInExactlyOneSafeFinancialOutcome() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            DisputeService disputeService = context.getBean(DisputeService.class);
            SettlementService settlementService = context.getBean(SettlementService.class);
            Fixture fixture = createFixture(jdbc);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            Callable<String> report = () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                disputeService.create(command(fixture));
                return "DISPUTE_CREATED";
            };
            Callable<String> payout = () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try {
                    settlementService.approve(SettlementApproveCommand.builder()
                            .workCaseId(fixture.workCaseId())
                            .approverUserId(fixture.ownerId())
                            .approverRole(UserRole.OWNER)
                            .idempotencyKey("IT-DISPUTE-RACE-" + UUID.randomUUID())
                            .build());
                    return "PAID";
                } catch (SettlementOnHoldException onHold) {
                    return "ON_HOLD";
                }
            };

            try {
                Future<String> reportFuture = executor.submit(report);
                Future<String> payoutFuture = executor.submit(payout);
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();

                assertEquals("DISPUTE_CREATED", reportFuture.get(15, TimeUnit.SECONDS));
                String payoutOutcome = payoutFuture.get(15, TimeUnit.SECONDS);
                assertEquals(1, count(jdbc,
                        "SELECT COUNT(*) FROM disputes WHERE work_case_id = ?",
                        fixture.workCaseId()));

                if ("PAID".equals(payoutOutcome)) {
                    assertEquals("COMPLETED", text(jdbc,
                            "SELECT status FROM settlements WHERE work_case_id = ?",
                            fixture.workCaseId()));
                    assertEquals("RELEASED", text(jdbc,
                            "SELECT status FROM escrows WHERE work_case_id = ?",
                            fixture.workCaseId()));
                    assertEquals(WAGE, number(jdbc,
                            "SELECT available_balance FROM wallets WHERE user_id = ?",
                            fixture.workerId()));
                    assertEquals(3, count(jdbc,
                            "SELECT COUNT(*) FROM wallet_transactions WHERE work_case_id = ?",
                            fixture.workCaseId()));
                } else {
                    assertEquals("ON_HOLD", payoutOutcome);
                    assertEquals("ON_HOLD", text(jdbc,
                            "SELECT status FROM settlements WHERE work_case_id = ?",
                            fixture.workCaseId()));
                    assertMoneyUnchanged(jdbc, fixture);
                }
            } finally {
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
                deleteFixture(jdbc, fixture);
            }
        }
    }

    @Test
    @Timeout(25)
    void fakeResolveClosesDisputeShowsSameResultAndResumesNormalPayout() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     fakeApplicationContext(DisputeReviewDecision.RESOLVE)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            DisputeService disputeService = context.getBean(DisputeService.class);
            SettlementService settlementService = context.getBean(SettlementService.class);
            Fixture fixture = createFixture(jdbc);
            LocalDateTime dueAt = dateTime(jdbc,
                    "SELECT due_at FROM settlements WHERE work_case_id = ?",
                    fixture.workCaseId());

            try {
                disputeService.create(command(fixture));
                assertEquals("ON_HOLD", text(jdbc,
                        "SELECT status FROM settlements WHERE work_case_id = ?",
                        fixture.workCaseId()));
                processReviewConcurrently(context, fixture.workCaseId());

                assertEquals("RESOLVED", text(jdbc,
                        "SELECT status FROM disputes WHERE work_case_id = ?",
                        fixture.workCaseId()));
                assertEquals("SCHEDULED", text(jdbc,
                        "SELECT status FROM settlements WHERE work_case_id = ?",
                        fixture.workCaseId()));
                assertEquals(dueAt, dateTime(jdbc,
                        "SELECT due_at FROM settlements WHERE work_case_id = ?",
                        fixture.workCaseId()));
                assertSameDemoResultForBothParties(disputeService, fixture, "RESOLVE");
                assertMoneyUnchanged(jdbc, fixture);

                SettlementResult payout = settlementService.approve(
                        SettlementApproveCommand.builder()
                                .workCaseId(fixture.workCaseId())
                                .approverUserId(fixture.ownerId())
                                .approverRole(UserRole.OWNER)
                                .idempotencyKey("IT-DISPUTE-PAYOUT-" + UUID.randomUUID())
                                .build());

                assertEquals("COMPLETED", payout.getStatus());
                assertEquals("RELEASED", text(jdbc,
                        "SELECT status FROM escrows WHERE work_case_id = ?",
                        fixture.workCaseId()));
                assertEquals(0L, number(jdbc,
                        "SELECT locked_balance FROM wallets WHERE user_id = ?",
                        fixture.ownerId()));
                assertEquals(WAGE, number(jdbc,
                        "SELECT available_balance FROM wallets WHERE user_id = ?",
                        fixture.workerId()));
                assertEquals(3, count(jdbc,
                        "SELECT COUNT(*) FROM wallet_transactions WHERE work_case_id = ?",
                        fixture.workCaseId()));
            } finally {
                deleteFixture(jdbc, fixture);
            }
        }
    }

    @Test
    @Timeout(25)
    void fakeRejectUnblocksNoShowRefundWithoutPayingWorker() {
        try (AnnotationConfigApplicationContext context =
                     fakeApplicationContext(DisputeReviewDecision.REJECT)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            DisputeService disputeService = context.getBean(DisputeService.class);
            SettlementService settlementService = context.getBean(SettlementService.class);
            Fixture fixture = createNoShowFixture(jdbc);
            NoShowRefundApproveCommand refundCommand = NoShowRefundApproveCommand.builder()
                    .workCaseId(fixture.workCaseId())
                    .approverUserId(fixture.ownerId())
                    .approverRole(UserRole.OWNER)
                    .idempotencyKey("IT-DISPUTE-REFUND-" + UUID.randomUUID())
                    .build();

            try {
                disputeService.create(command(fixture));
                assertThrows(
                        SettlementOnHoldException.class,
                        () -> settlementService.approveNoShowRefund(refundCommand));
                assertMoneyUnchanged(jdbc, fixture);

                processPendingReview(context, fixture.workCaseId());
                assertEquals("REJECTED", text(jdbc,
                        "SELECT status FROM disputes WHERE work_case_id = ?",
                        fixture.workCaseId()));
                assertEquals("WAITING", text(jdbc,
                        "SELECT status FROM settlements WHERE work_case_id = ?",
                        fixture.workCaseId()));

                SettlementResult refund = settlementService.approveNoShowRefund(refundCommand);

                assertEquals("REFUNDED", refund.getStatus());
                assertEquals(0L, refund.getWorkerPaidAmount());
                assertEquals(WAGE, refund.getOwnerRefundAmount());
                assertEquals(WAGE, number(jdbc,
                        "SELECT available_balance FROM wallets WHERE user_id = ?",
                        fixture.ownerId()));
                assertEquals(0L, number(jdbc,
                        "SELECT available_balance + locked_balance FROM wallets WHERE user_id = ?",
                        fixture.workerId()));
            } finally {
                deleteFixture(jdbc, fixture);
            }
        }
    }

    @Test
    @Timeout(20)
    void fakeNeedsMoreInfoShowsResultWhileKeepingSettlementOnHold() {
        try (AnnotationConfigApplicationContext context =
                     fakeApplicationContext(DisputeReviewDecision.NEEDS_MORE_INFO)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            DisputeService disputeService = context.getBean(DisputeService.class);
            Fixture fixture = createFixture(jdbc);

            try {
                disputeService.create(command(fixture));
                processPendingReview(context, fixture.workCaseId());

                assertEquals("UNDER_REVIEW", text(jdbc,
                        "SELECT status FROM disputes WHERE work_case_id = ?",
                        fixture.workCaseId()));
                assertEquals("ON_HOLD", text(jdbc,
                        "SELECT status FROM settlements WHERE work_case_id = ?",
                        fixture.workCaseId()));
                DisputeListItemResponse item = disputeService.findPage(
                        fixture.workCaseId(), fixture.workerId(), UserRole.WORKER, 0, 20)
                        .getContent().get(0);
                assertEquals("NEEDS_MORE_INFO", item.getDemoReview().getDecision());
                assertEquals(
                        List.of("ADDITIONAL_EVIDENCE_NEEDED"),
                        item.getDemoReview().getReasonCodes());
                assertMoneyUnchanged(jdbc, fixture);
            } finally {
                deleteFixture(jdbc, fixture);
            }
        }
    }

    @Test
    @Timeout(25)
    void transientProviderFailureRetriesAndLateResponseCannotOverrideResult() {
        try (AnnotationConfigApplicationContext context =
                     fakeApplicationContext(DisputeReviewDecision.RESOLVE)) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            DisputeService disputeService = context.getBean(DisputeService.class);
            SettlementService settlementService = context.getBean(SettlementService.class);
            DisputeReviewQueueService queueService =
                    context.getBean(DisputeReviewQueueService.class);
            Fixture fixture = createFixture(jdbc);

            try {
                disputeService.create(command(fixture));
                DisputeReviewCandidate candidate = pendingCandidate(context, fixture.workCaseId());
                DisputeReviewExecution execution = queueService.claim(candidate);
                assertNotNull(execution);
                assertTrue(queueService.fail(execution, "PROVIDER_TIMEOUT"));

                boolean lateApplied = queueService.complete(
                        execution,
                        new DisputeReviewProviderResult(
                                "late-response",
                                new DisputeReviewResult(
                                        DisputeReviewDecision.RESOLVE,
                                        List.of("LATE_RESULT"),
                                        "늦은 결과입니다.",
                                        new BigDecimal("0.900"))));

                assertFalse(lateApplied);
                assertEquals("UNDER_REVIEW", text(jdbc,
                        "SELECT status FROM disputes WHERE work_case_id = ?",
                        fixture.workCaseId()));
                assertEquals(1L, count(jdbc,
                        "SELECT COUNT(*) FROM dispute_ai_reviews"
                                + " WHERE dispute_id = (SELECT id FROM disputes"
                                + " WHERE work_case_id = ?) AND status = 'FAILED'",
                        fixture.workCaseId()));
                assertEquals(1L, count(jdbc,
                        "SELECT COUNT(*) FROM dispute_ai_reviews"
                                + " WHERE dispute_id = (SELECT id FROM disputes"
                                + " WHERE work_case_id = ?) AND status = 'PENDING'",
                        fixture.workCaseId()));
                assertEquals("ON_HOLD", text(jdbc,
                        "SELECT status FROM settlements WHERE work_case_id = ?",
                        fixture.workCaseId()));
                assertThrows(
                        SettlementOnHoldException.class,
                        () -> settlementService.approve(SettlementApproveCommand.builder()
                                .workCaseId(fixture.workCaseId())
                                .approverUserId(fixture.ownerId())
                                .approverRole(UserRole.OWNER)
                                .idempotencyKey("IT-DISPUTE-LATE-" + UUID.randomUUID())
                                .build()));
                assertMoneyUnchanged(jdbc, fixture);

                processPendingReview(context, fixture.workCaseId());

                assertEquals("RESOLVED", text(jdbc,
                        "SELECT status FROM disputes WHERE work_case_id = ?",
                        fixture.workCaseId()));
                assertEquals("SCHEDULED", text(jdbc,
                        "SELECT status FROM settlements WHERE work_case_id = ?",
                        fixture.workCaseId()));
                assertEquals(1L, count(jdbc,
                        "SELECT COUNT(*) FROM dispute_ai_reviews"
                                + " WHERE dispute_id = (SELECT id FROM disputes"
                                + " WHERE work_case_id = ?) AND status = 'COMPLETED'",
                        fixture.workCaseId()));
                assertMoneyUnchanged(jdbc, fixture);
            } finally {
                deleteFixture(jdbc, fixture);
            }
        }
    }

    private static AnnotationConfigApplicationContext fakeApplicationContext(
            DisputeReviewDecision decision) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                context,
                "dispute.review.mode=FAKE",
                "dispute.review.demo-confirmed=true",
                "dispute.review.fake-decision=" + decision.name(),
                "dispute.review.fixed-delay-ms=600000",
                "dispute.review.initial-delay-ms=600000"
        );
        context.register(RootConfig.class);
        context.refresh();
        return context;
    }

    private static void processPendingReview(
            AnnotationConfigApplicationContext context,
            long workCaseId) {
        context.getBean(DisputeReviewProcessor.class)
                .process(pendingCandidate(context, workCaseId));
    }

    private static void processReviewConcurrently(
            AnnotationConfigApplicationContext context,
            long workCaseId) throws Exception {
        DisputeReviewCandidate candidate = pendingCandidate(context, workCaseId);
        DisputeReviewProcessor processor = context.getBean(DisputeReviewProcessor.class);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> task = () -> {
            ready.countDown();
            assertTrue(start.await(5, TimeUnit.SECONDS));
            processor.process(candidate);
            return null;
        };
        try {
            Future<Void> first = executor.submit(task);
            Future<Void> second = executor.submit(task);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static DisputeReviewCandidate pendingCandidate(
            AnnotationConfigApplicationContext context,
            long workCaseId) {
        DisputeReviewMapper mapper = context.getBean(DisputeReviewMapper.class);
        return mapper.findCandidates(mapper.currentDatabaseTime(), 100)
                .stream()
                .filter(candidate -> candidate.getWorkCaseId() == workCaseId)
                .findFirst()
                .orElseThrow();
    }

    private static void assertSameDemoResultForBothParties(
            DisputeService service,
            Fixture fixture,
            String expectedDecision) {
        DisputeListItemResponse ownerItem = service.findPage(
                fixture.workCaseId(), fixture.ownerId(), UserRole.OWNER, 0, 20)
                .getContent().get(0);
        DisputeListItemResponse workerItem = service.findPage(
                fixture.workCaseId(), fixture.workerId(), UserRole.WORKER, 0, 20)
                .getContent().get(0);

        assertEquals(DisputeStatus.RESOLVED, ownerItem.getStatus());
        assertNotNull(ownerItem.getDemoReview());
        assertNotNull(workerItem.getDemoReview());
        assertEquals("SIMULATED_LLM", ownerItem.getDemoReview().getSource());
        assertEquals(expectedDecision, ownerItem.getDemoReview().getDecision());
        assertEquals(
                ownerItem.getDemoReview().getReasonCodes(),
                workerItem.getDemoReview().getReasonCodes());
        assertEquals(
                ownerItem.getDemoReview().getSummary(),
                workerItem.getDemoReview().getSummary());
        assertEquals(
                ownerItem.getDemoReview().getReviewedAt(),
                workerItem.getDemoReview().getReviewedAt());
    }

    private static DisputeCreateCommand command(Fixture fixture) {
        return DisputeCreateCommand.builder()
                .workCaseId(fixture.workCaseId())
                .requesterUserId(fixture.workerId())
                .requesterRole(UserRole.WORKER)
                .title("약정 일급 지급 확인")
                .content("약정 일급 지급 여부를 확인해주세요.")
                .build();
    }

    private static Fixture createFixture(JdbcTemplate jdbc) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String ownerLogin = "it_dispute_owner_" + token;
        String workerLogin = "it_dispute_worker_" + token;
        insertUser(jdbc, ownerLogin, "OWNER");
        insertUser(jdbc, workerLogin, "WORKER");
        long ownerId = idBy(jdbc, "users", "login_id", ownerLogin);
        long workerId = idBy(jdbc, "users", "login_id", workerLogin);

        jdbc.update(
                "INSERT INTO wallets (user_id, available_balance, locked_balance)"
                        + " VALUES (?, 0, ?), (?, 0, 0)",
                ownerId, WAGE, workerId
        );
        String businessNumber = String.format(
                "%010d", Integer.toUnsignedLong(token.hashCode()));
        jdbc.update(
                "INSERT INTO workplaces"
                        + " (owner_user_id, business_registration_number, name, representative_name,"
                        + " road_address, phone, status)"
                        + " VALUES (?, ?, '분쟁 테스트 사업장', '테스트 대표',"
                        + " '서울특별시 테스트로 1', '02-0000-0000', 'ACTIVE')",
                ownerId, businessNumber
        );
        long workplaceId = idBy(
                jdbc, "workplaces", "business_registration_number", businessNumber);
        String workTitle = "dispute-" + token;
        jdbc.update(
                "INSERT INTO work_cases"
                        + " (employer_id, worker_id, workplace_id, title, starts_at, ends_at,"
                        + " break_minutes, break_paid, workplace_name, workplace_address,"
                        + " allowed_radius_meters, agreed_wage, terms_version, status)"
                        + " VALUES (?, ?, ?, ?, '2030-01-01 09:00:00', '2030-01-01 18:00:00',"
                        + " 60, 0, '분쟁 테스트 사업장', '서울특별시 테스트로 1',"
                        + " 100, ?, 1, 'COMPLETED')",
                ownerId, workerId, workplaceId, workTitle, WAGE
        );
        long workCaseId = idBy(jdbc, "work_cases", "title", workTitle);
        jdbc.update(
                "INSERT INTO escrows (work_case_id, amount, status, held_at)"
                        + " VALUES (?, ?, 'HELD', NOW(6))",
                workCaseId, WAGE
        );
        long escrowId = idBy(jdbc, "escrows", "work_case_id", workCaseId);
        jdbc.update(
                "INSERT INTO settlements (work_case_id, amount, status, due_at)"
                        + " VALUES (?, ?, 'SCHEDULED', DATE_ADD(NOW(6), INTERVAL 1 HOUR))",
                workCaseId, WAGE
        );
        long ownerWalletId = idBy(jdbc, "wallets", "user_id", ownerId);
        jdbc.update(
                "INSERT INTO wallet_transactions"
                        + " (wallet_id, work_case_id, transaction_type, amount,"
                        + " available_before, available_after, locked_before, locked_after,"
                        + " reference_type, reference_id, idempotency_key)"
                        + " VALUES (?, ?, 'ESCROW_HOLD', ?, ?, 0, 0, ?,"
                        + " 'ESCROW', ?, ?)",
                ownerWalletId,
                workCaseId,
                WAGE,
                WAGE,
                WAGE,
                escrowId,
                "IT-DISPUTE-HOLD-" + token
        );
        return new Fixture(ownerId, workerId, workplaceId, workCaseId);
    }

    private static Fixture createNoShowFixture(JdbcTemplate jdbc) {
        Fixture fixture = createFixture(jdbc);
        jdbc.update(
                "UPDATE work_cases SET status = 'NO_SHOW' WHERE id = ?",
                fixture.workCaseId());
        jdbc.update(
                "UPDATE settlements SET status = 'WAITING', due_at = NULL"
                        + " WHERE work_case_id = ?",
                fixture.workCaseId());
        return fixture;
    }

    private static void assertMoneyUnchanged(JdbcTemplate jdbc, Fixture fixture) {
        assertEquals("HELD", text(
                jdbc, "SELECT status FROM escrows WHERE work_case_id = ?", fixture.workCaseId()));
        assertEquals(WAGE, number(jdbc,
                "SELECT locked_balance FROM wallets WHERE user_id = ?", fixture.ownerId()));
        assertEquals(0L, number(jdbc,
                "SELECT available_balance FROM wallets WHERE user_id = ?", fixture.workerId()));
        assertEquals(1, count(
                jdbc,
                "SELECT COUNT(*) FROM wallet_transactions WHERE work_case_id = ?",
                fixture.workCaseId()
        ));
    }

    private static void deleteFixture(JdbcTemplate jdbc, Fixture fixture) {
        jdbc.update("DELETE FROM idempotency_requests WHERE user_id IN (?, ?)",
                fixture.ownerId(), fixture.workerId());
        jdbc.update(
                "DELETE FROM dispute_ai_reviews WHERE dispute_id IN"
                        + " (SELECT id FROM disputes WHERE work_case_id = ?)",
                fixture.workCaseId());
        jdbc.update("DELETE FROM disputes WHERE work_case_id = ?", fixture.workCaseId());
        jdbc.update("DELETE FROM wallet_transactions WHERE work_case_id = ?", fixture.workCaseId());
        jdbc.update("DELETE FROM settlements WHERE work_case_id = ?", fixture.workCaseId());
        jdbc.update("DELETE FROM escrows WHERE work_case_id = ?", fixture.workCaseId());
        jdbc.update("DELETE FROM work_cases WHERE id = ?", fixture.workCaseId());
        jdbc.update("DELETE FROM workplaces WHERE id = ?", fixture.workplaceId());
        jdbc.update("DELETE FROM wallets WHERE user_id IN (?, ?)",
                fixture.ownerId(), fixture.workerId());
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", fixture.ownerId(), fixture.workerId());
    }

    private static void insertUser(JdbcTemplate jdbc, String loginId, String role) {
        jdbc.update(
                "INSERT INTO users (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'dispute-test-hash', '분쟁 테스트', ?, 'ACTIVE')",
                loginId, loginId + "@example.test", role
        );
    }

    private static long idBy(JdbcTemplate jdbc, String table, String column, Object value) {
        return jdbc.queryForObject(
                "SELECT id FROM " + table + " WHERE " + column + " = ?", Long.class, value);
    }

    private static int count(JdbcTemplate jdbc, String sql, Object argument) {
        return jdbc.queryForObject(sql, Integer.class, argument);
    }

    private static long number(JdbcTemplate jdbc, String sql, Object argument) {
        return jdbc.queryForObject(sql, Long.class, argument);
    }

    private static String text(JdbcTemplate jdbc, String sql, Object argument) {
        return jdbc.queryForObject(sql, String.class, argument);
    }

    private static LocalDateTime dateTime(JdbcTemplate jdbc, String sql, Object argument) {
        return jdbc.queryForObject(sql, LocalDateTime.class, argument);
    }

    private record Fixture(long ownerId, long workerId, long workplaceId, long workCaseId) {
    }
}
