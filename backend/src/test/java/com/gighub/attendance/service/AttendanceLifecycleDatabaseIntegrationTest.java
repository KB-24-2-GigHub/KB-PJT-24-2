package com.gighub.attendance.service;

import com.gighub.attendance.mapper.AttendanceLifecycleMapper;
import com.gighub.config.RootConfig;
import com.gighub.document.storage.ContractStorageKeys;
import com.gighub.document.storage.DocumentStorageAdapter;
import com.gighub.document.storage.DocumentStorageProperties;
import com.gighub.document.storage.Sha256;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.service.WorkLifecycleCommandService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실제 MySQL 잠금과 제약에서 근태 자동 판정의 멱등성·자금 불변성을 검증합니다. */
@Tag("database")
class AttendanceLifecycleDatabaseIntegrationTest {

    private static final long WAGE = 300_000L;
    private static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 2, 12, 0);

    @Test
    @Timeout(30)
    void completeAggregateEntersReadyExactlyOnceAcrossConcurrentExecutors() throws Exception {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            Fixture fixture = createFixture(
                    context,
                    jdbcTemplate,
                    "ACCEPTED",
                    NOW.plusMinutes(30),
                    NOW.plusHours(8));
            FundsSnapshot before = fundsSnapshot(jdbcTemplate, fixture);

            try {
                List<Boolean> results = runConcurrently(
                        () -> context.getBean(AttendanceLifecycleTransitionExecutor.class)
                                .advanceToReady(fixture.workCaseId(), NOW));

                assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
                assertEquals("READY", workCaseStatus(jdbcTemplate, fixture));
                assertEquals(before, fundsSnapshot(jdbcTemplate, fixture));
            } finally {
                deleteFixture(context, jdbcTemplate, fixture);
            }
        }
    }

    @Test
    @Timeout(30)
    void terminalTransitionsAreExclusiveIdempotentAndDoNotMoveFunds() throws Exception {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            Fixture noShow = createFixture(
                    context,
                    jdbcTemplate,
                    "READY",
                    NOW.minusHours(1),
                    NOW.plusHours(7));
            Fixture checkoutMissing = createFixture(
                    context,
                    jdbcTemplate,
                    "IN_PROGRESS",
                    NOW.minusHours(10),
                    NOW.minusHours(2));
            insertAttendance(jdbcTemplate, checkoutMissing, "CHECK_IN");
            FundsSnapshot noShowBefore = fundsSnapshot(jdbcTemplate, noShow);
            FundsSnapshot checkoutBefore = fundsSnapshot(jdbcTemplate, checkoutMissing);

            try {
                AttendanceLifecycleTransitionExecutor executor =
                        context.getBean(AttendanceLifecycleTransitionExecutor.class);
                WorkLifecycleCommandService workLifecycleCommandService =
                        context.getBean(WorkLifecycleCommandService.class);
                List<Boolean> noShowResults = runConcurrently(
                        () -> executor.advanceToNoShow(noShow.workCaseId(), NOW));
                List<Boolean> checkoutResults = runConcurrently(
                        () -> executor.advanceToCheckoutMissing(
                                checkoutMissing.workCaseId(), NOW));

                assertEquals(1, noShowResults.stream().filter(Boolean::booleanValue).count());
                assertEquals(1, checkoutResults.stream().filter(Boolean::booleanValue).count());
                assertEquals("NO_SHOW", workCaseStatus(jdbcTemplate, noShow));
                assertEquals(
                        "CHECK_OUT_MISSING",
                        workCaseStatus(jdbcTemplate, checkoutMissing));
                assertEquals(noShowBefore, fundsSnapshot(jdbcTemplate, noShow));
                assertEquals(checkoutBefore, fundsSnapshot(jdbcTemplate, checkoutMissing));
            } finally {
                deleteFixture(context, jdbcTemplate, checkoutMissing);
                deleteFixture(context, jdbcTemplate, noShow);
            }
        }
    }

    @Test
    @Timeout(30)
    void successfulAttendanceFactsExcludeContradictoryTerminalCandidates() {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            Fixture checkedIn = createFixture(
                    context,
                    jdbcTemplate,
                    "READY",
                    NOW.minusHours(1),
                    NOW.plusHours(7));
            Fixture checkedOut = createFixture(
                    context,
                    jdbcTemplate,
                    "IN_PROGRESS",
                    NOW.minusHours(10),
                    NOW.minusHours(2));
            insertAttendance(jdbcTemplate, checkedIn, "CHECK_IN");
            insertAttendance(jdbcTemplate, checkedOut, "CHECK_IN");
            insertAttendance(jdbcTemplate, checkedOut, "CHECK_OUT");

            try {
                AttendanceLifecycleMapper mapper =
                        context.getBean(AttendanceLifecycleMapper.class);
                AttendanceLifecycleTransitionExecutor executor =
                        context.getBean(AttendanceLifecycleTransitionExecutor.class);

                assertFalse(mapper.findNoShowCandidateIds(NOW.minusHours(1), 100)
                        .contains(checkedIn.workCaseId()));
                assertFalse(mapper.findCheckoutMissingCandidateIds(NOW.minusHours(2), 100)
                        .contains(checkedOut.workCaseId()));
                assertFalse(executor.advanceToNoShow(checkedIn.workCaseId(), NOW));
                assertFalse(executor.advanceToCheckoutMissing(checkedOut.workCaseId(), NOW));
                assertEquals("READY", workCaseStatus(jdbcTemplate, checkedIn));
                assertEquals("IN_PROGRESS", workCaseStatus(jdbcTemplate, checkedOut));
            } finally {
                deleteFixture(context, jdbcTemplate, checkedOut);
                deleteFixture(context, jdbcTemplate, checkedIn);
            }
        }
    }

    @Test
    @Timeout(30)
    void candidateBatchLimitDrainsRemainingRowsOnTheNextRun() {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            List<Fixture> fixtures = List.of(
                    createFixture(
                            context,
                            jdbcTemplate,
                            "READY",
                            NOW.minusHours(1),
                            NOW.plusHours(7)),
                    createFixture(
                            context,
                            jdbcTemplate,
                            "READY",
                            NOW.minusHours(1),
                            NOW.plusHours(7)),
                    createFixture(
                            context,
                            jdbcTemplate,
                            "READY",
                            NOW.minusHours(1),
                            NOW.plusHours(7)));

            try {
                AttendanceLifecycleMapper mapper =
                        context.getBean(AttendanceLifecycleMapper.class);
                AttendanceLifecycleTransitionExecutor executor =
                        context.getBean(AttendanceLifecycleTransitionExecutor.class);

                List<Long> firstBatch = mapper.findNoShowCandidateIds(NOW.minusHours(1), 2);
                assertEquals(2, firstBatch.size());
                firstBatch.forEach(workCaseId ->
                        assertTrue(executor.advanceToNoShow(workCaseId, NOW)));

                List<Long> nextBatch = mapper.findNoShowCandidateIds(NOW.minusHours(1), 2);
                assertEquals(1, nextBatch.size());
                assertTrue(executor.advanceToNoShow(nextBatch.get(0), NOW));
                assertEquals(3, fixtures.stream()
                        .filter(fixture -> "NO_SHOW".equals(workCaseStatus(jdbcTemplate, fixture)))
                        .count());
            } finally {
                for (int index = fixtures.size() - 1; index >= 0; index--) {
                    deleteFixture(context, jdbcTemplate, fixtures.get(index));
                }
            }
        }
    }

    @Test
    @Timeout(30)
    void lateCheckInAndNoShowRaceCommitOnlyOneOutcome() throws Exception {
        try (AnnotationConfigApplicationContext context = applicationContext()) {
            JdbcTemplate jdbcTemplate = jdbcTemplate(context);
            Fixture fixture = createFixture(
                    context,
                    jdbcTemplate,
                    "READY",
                    NOW.minusHours(1),
                    NOW.plusHours(7));
            FundsSnapshot before = fundsSnapshot(jdbcTemplate, fixture);

            try {
                AttendanceLifecycleTransitionExecutor executor =
                        context.getBean(AttendanceLifecycleTransitionExecutor.class);
                WorkLifecycleCommandService workLifecycleCommandService =
                        context.getBean(WorkLifecycleCommandService.class);
                TransactionTemplate transaction = new TransactionTemplate(
                        context.getBean(PlatformTransactionManager.class));

                runConcurrently(
                        () -> Boolean.TRUE.equals(transaction.execute(status -> {
                            var row = workLifecycleCommandService.lock(fixture.workCaseId());
                            if (row == null || row.status() != WorkCaseStatus.READY) {
                                return false;
                            }
                            insertAttendance(jdbcTemplate, fixture, "CHECK_IN");
                            return workLifecycleCommandService.transition(
                                    fixture.workCaseId(),
                                    WorkCaseStatus.READY,
                                    WorkCaseStatus.IN_PROGRESS);
                        })),
                        () -> executor.advanceToNoShow(fixture.workCaseId(), NOW));

                String finalStatus = workCaseStatus(jdbcTemplate, fixture);
                long checkInCount = number(
                        jdbcTemplate,
                        "SELECT COUNT(*) FROM attendance_records"
                                + " WHERE work_case_id = ?"
                                + " AND attendance_type = 'CHECK_IN'"
                                + " AND result = 'SUCCESS'",
                        fixture.workCaseId());
                assertTrue("IN_PROGRESS".equals(finalStatus) || "NO_SHOW".equals(finalStatus));
                assertEquals("IN_PROGRESS".equals(finalStatus) ? 1L : 0L, checkInCount);
                assertEquals(before, fundsSnapshot(jdbcTemplate, fixture));
            } finally {
                deleteFixture(context, jdbcTemplate, fixture);
            }
        }
    }

    private AnnotationConfigApplicationContext applicationContext() {
        return new AnnotationConfigApplicationContext(RootConfig.class);
    }

    private JdbcTemplate jdbcTemplate(AnnotationConfigApplicationContext context) {
        return new JdbcTemplate(context.getBean(DataSource.class));
    }

    private List<Boolean> runConcurrently(Callable<Boolean> action) throws Exception {
        return runConcurrently(action, action);
    }

    private List<Boolean> runConcurrently(
            Callable<Boolean> firstAction,
            Callable<Boolean> secondAction) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Boolean> firstParticipant = concurrentParticipant(
                    firstAction, ready, start);
            Callable<Boolean> secondParticipant = concurrentParticipant(
                    secondAction, ready, start);
            List<Future<Boolean>> futures = List.of(
                    pool.submit(firstParticipant),
                    pool.submit(secondParticipant));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Boolean> concurrentParticipant(
            Callable<Boolean> action,
            CountDownLatch ready,
            CountDownLatch start) {
        return () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("동시 판정 시작 신호를 기다리지 못했습니다.");
                }
                return action.call();
        };
    }

    private Fixture createFixture(
            AnnotationConfigApplicationContext context,
            JdbcTemplate jdbcTemplate,
            String status,
            LocalDateTime startsAt,
            LocalDateTime endsAt) {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String ownerLogin = "att163_owner_" + token;
        String workerLogin = "att163_worker_" + token;
        String businessNumber = String.format("%010d", Integer.toUnsignedLong(token.hashCode()));

        jdbcTemplate.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'integration-test', '테스트 고용주', 'OWNER', 'ACTIVE')",
                ownerLogin,
                ownerLogin + "@example.invalid");
        jdbcTemplate.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'integration-test', '테스트 근로자', 'WORKER', 'ACTIVE')",
                workerLogin,
                workerLogin + "@example.invalid");
        long ownerId = idBy(jdbcTemplate, "users", "login_id", ownerLogin);
        long workerId = idBy(jdbcTemplate, "users", "login_id", workerLogin);

        jdbcTemplate.update(
                "INSERT INTO wallets"
                        + " (user_id, currency, available_balance, locked_balance)"
                        + " VALUES (?, 'KRW', 0, ?)",
                ownerId,
                WAGE);
        jdbcTemplate.update(
                "INSERT INTO wallets"
                        + " (user_id, currency, available_balance, locked_balance)"
                        + " VALUES (?, 'KRW', 0, 0)",
                workerId);
        long ownerWalletId = idBy(jdbcTemplate, "wallets", "user_id", ownerId);
        long workerWalletId = idBy(jdbcTemplate, "wallets", "user_id", workerId);

        jdbcTemplate.update(
                "INSERT INTO workplaces"
                        + " (owner_user_id, business_registration_number, name,"
                        + " representative_name, road_address, detail_address, phone,"
                        + " latitude, longitude, radius_meters, status)"
                        + " VALUES (?, ?, '테스트 사업장', '테스트 고용주',"
                        + " '서울시 테스트로 1', NULL, '02-0000-0000',"
                        + " 37.5000000, 127.0000000, 100, 'ACTIVE')",
                ownerId,
                businessNumber);
        long workplaceId = idBy(
                jdbcTemplate,
                "workplaces",
                "business_registration_number",
                businessNumber);

        String title = "ATT-163-" + token;
        jdbcTemplate.update(
                "INSERT INTO work_cases"
                        + " (employer_id, worker_id, workplace_id, title, starts_at, ends_at,"
                        + " break_minutes, break_paid, workplace_name, workplace_address,"
                        + " workplace_latitude, workplace_longitude, allowed_radius_meters,"
                        + " agreed_wage, terms_version, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 60, 0, '테스트 사업장',"
                        + " '서울시 테스트로 1', 37.5000000, 127.0000000, 100, ?, 1, ?)",
                ownerId,
                workerId,
                workplaceId,
                title,
                startsAt,
                endsAt,
                WAGE,
                status);
        long workCaseId = idBy(jdbcTemplate, "work_cases", "title", title);

        insertAcceptedAggregate(
                context,
                jdbcTemplate,
                token,
                ownerId,
                workerId,
                ownerWalletId,
                workCaseId,
                startsAt,
                endsAt);

        return new Fixture(
                ownerId,
                workerId,
                ownerWalletId,
                workerWalletId,
                workplaceId,
                workCaseId);
    }

    private void insertAcceptedAggregate(
            AnnotationConfigApplicationContext context,
            JdbcTemplate jdbcTemplate,
            String token,
            long ownerId,
            long workerId,
            long ownerWalletId,
            long workCaseId,
            LocalDateTime startsAt,
            LocalDateTime endsAt) {
        jdbcTemplate.update(
                "INSERT INTO work_invitations"
                        + " (work_case_id, token_hash, status, expected_terms_version,"
                        + " expires_at, accepted_by_user_id, accepted_terms_version, accepted_at)"
                        + " VALUES (?, UNHEX(SHA2(?, 256)), 'ACCEPTED', 1, ?, ?, 1, ?)",
                workCaseId,
                "ATT-163-INVITE-" + token,
                endsAt,
                workerId,
                NOW.minusDays(1));
        jdbcTemplate.update(
                "INSERT INTO work_contracts"
                        + " (work_case_id, employer_id, worker_id, title, starts_at, ends_at,"
                        + " break_minutes, break_paid, workplace_name, workplace_address,"
                        + " workplace_latitude, workplace_longitude, allowed_radius_meters,"
                        + " agreed_wage, source_terms_version, terms_snapshot, accepted_at)"
                        + " VALUES (?, ?, ?, '테스트 근로계약', ?, ?, 60, 0, '테스트 사업장',"
                        + " '서울시 테스트로 1', 37.5000000, 127.0000000, 100, ?, 1,"
                        + " JSON_OBJECT('test', TRUE), ?)",
                workCaseId,
                ownerId,
                workerId,
                startsAt,
                endsAt,
                WAGE,
                NOW.minusDays(1));
        jdbcTemplate.update(
                "INSERT INTO escrows (work_case_id, amount, status, held_at)"
                        + " VALUES (?, ?, 'HELD', ?)",
                workCaseId,
                WAGE,
                NOW.minusDays(1));
        long escrowId = idBy(jdbcTemplate, "escrows", "work_case_id", workCaseId);
        jdbcTemplate.update(
                "INSERT INTO settlements (work_case_id, amount, status, due_at)"
                        + " VALUES (?, ?, 'WAITING', NULL)",
                workCaseId,
                WAGE);
        jdbcTemplate.update(
                "INSERT INTO wallet_transactions"
                        + " (wallet_id, work_case_id, transaction_type, amount,"
                        + " available_before, available_after, locked_before, locked_after,"
                        + " reference_type, reference_id, idempotency_key)"
                        + " VALUES (?, ?, 'ESCROW_HOLD', ?, 0, 0, 0, ?, 'ESCROW', ?, ?)",
                ownerWalletId,
                workCaseId,
                WAGE,
                WAGE,
                escrowId,
                "ATT-163-HOLD-" + token);

        insertSignedContract(
                context,
                jdbcTemplate,
                ownerId,
                workerId,
                workCaseId);
    }

    private void insertSignedContract(
            AnnotationConfigApplicationContext context,
            JdbcTemplate jdbcTemplate,
            long ownerId,
            long workerId,
            long workCaseId) {
        jdbcTemplate.update(
                "INSERT INTO documents"
                        + " (created_by_user_id, owner_user_id, work_case_id,"
                        + " document_type, status, issued_on)"
                        + " VALUES (?, ?, ?, 'EMPLOYMENT_CONTRACT', 'ACTIVE', '2030-01-01')",
                ownerId,
                ownerId,
                workCaseId);
        long documentId = idBy(jdbcTemplate, "documents", "work_case_id", workCaseId);

        byte[] original = "original-contract".getBytes(StandardCharsets.UTF_8);
        byte[] signed = "signed-contract".getBytes(StandardCharsets.UTF_8);
        byte[] originalChecksum = Sha256.digest(original);
        byte[] signedChecksum = Sha256.digest(signed);
        String originalKey = ContractStorageKeys.finalKey(workCaseId, documentId, 1);
        String signedKey = ContractStorageKeys.finalKey(workCaseId, documentId, 2);

        jdbcTemplate.update(
                "INSERT INTO document_versions"
                        + " (document_id, version_no, version_type, storage_key,"
                        + " mime_type, size_bytes, checksum)"
                        + " VALUES (?, 1, 'ORIGINAL', ?, 'application/pdf', ?, ?)",
                documentId,
                originalKey,
                original.length,
                originalChecksum);
        jdbcTemplate.update(
                "INSERT INTO document_versions"
                        + " (document_id, version_no, version_type, storage_key,"
                        + " mime_type, size_bytes, checksum)"
                        + " VALUES (?, 2, 'SIGNED', ?, 'application/pdf', ?, ?)",
                documentId,
                signedKey,
                signed.length,
                signedChecksum);
        long originalVersionId = idBy(
                jdbcTemplate,
                "document_versions",
                "storage_key",
                originalKey);
        long signedVersionId = idBy(
                jdbcTemplate,
                "document_versions",
                "storage_key",
                signedKey);

        jdbcTemplate.update(
                "INSERT INTO document_signatures"
                        + " (document_id, source_version_id, signed_version_id, signer_user_id,"
                        + " source_checksum, signed_checksum, typed_name, signature_method,"
                        + " consented_at, signed_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, '테스트 근로자', 'TYPED_NAME', ?, ?)",
                documentId,
                originalVersionId,
                signedVersionId,
                workerId,
                originalChecksum,
                signedChecksum,
                NOW.minusDays(1),
                NOW.minusDays(1));
        jdbcTemplate.update(
                "INSERT INTO document_shares"
                        + " (document_id, work_case_id, shared_with_user_id, purpose, status)"
                        + " VALUES (?, ?, ?, 'CONTRACT_PARTY', 'ACTIVE')",
                documentId,
                workCaseId,
                workerId);

        DocumentStorageAdapter storage = context.getBean(DocumentStorageAdapter.class);
        String pendingKey = ContractStorageKeys.pendingKey(workCaseId, documentId, 2);
        storage.writePending(pendingKey, signed);
        storage.promote(pendingKey, signedKey, signedChecksum);
    }

    private void insertAttendance(
            JdbcTemplate jdbcTemplate,
            Fixture fixture,
            String attendanceType) {
        jdbcTemplate.update(
                "INSERT INTO attendance_records"
                        + " (work_case_id, worker_id, attendance_type, captured_at, attempted_at,"
                        + " result) VALUES (?, ?, ?, ?, ?, 'SUCCESS')",
                fixture.workCaseId(),
                fixture.workerId(),
                attendanceType,
                NOW.minusHours(3),
                NOW.minusHours(3));
    }

    private FundsSnapshot fundsSnapshot(JdbcTemplate jdbcTemplate, Fixture fixture) {
        return new FundsSnapshot(
                number(jdbcTemplate, "SELECT available_balance FROM wallets WHERE id = ?",
                        fixture.ownerWalletId()),
                number(jdbcTemplate, "SELECT locked_balance FROM wallets WHERE id = ?",
                        fixture.ownerWalletId()),
                number(jdbcTemplate, "SELECT available_balance FROM wallets WHERE id = ?",
                        fixture.workerWalletId()),
                text(jdbcTemplate, "SELECT status FROM escrows WHERE work_case_id = ?",
                        fixture.workCaseId()),
                text(jdbcTemplate, "SELECT status FROM settlements WHERE work_case_id = ?",
                        fixture.workCaseId()),
                jdbcTemplate.queryForObject(
                        "SELECT due_at FROM settlements WHERE work_case_id = ?",
                        LocalDateTime.class,
                        fixture.workCaseId()),
                number(jdbcTemplate,
                        "SELECT COUNT(*) FROM wallet_transactions WHERE work_case_id = ?",
                        fixture.workCaseId()));
    }

    private String workCaseStatus(JdbcTemplate jdbcTemplate, Fixture fixture) {
        return text(
                jdbcTemplate,
                "SELECT status FROM work_cases WHERE id = ?",
                fixture.workCaseId());
    }

    private void deleteFixture(
            AnnotationConfigApplicationContext context,
            JdbcTemplate jdbcTemplate,
            Fixture fixture) {
        jdbcTemplate.update(
                "DELETE FROM attendance_records WHERE work_case_id = ?",
                fixture.workCaseId());
        jdbcTemplate.update(
                "DELETE FROM wallet_transactions WHERE work_case_id = ?",
                fixture.workCaseId());
        jdbcTemplate.update(
                "DELETE FROM document_access_logs WHERE document_id IN"
                        + " (SELECT id FROM documents WHERE work_case_id = ?)",
                fixture.workCaseId());
        jdbcTemplate.update(
                "DELETE FROM document_signatures WHERE document_id IN"
                        + " (SELECT id FROM documents WHERE work_case_id = ?)",
                fixture.workCaseId());
        jdbcTemplate.update(
                "DELETE FROM document_shares WHERE work_case_id = ?",
                fixture.workCaseId());
        jdbcTemplate.update(
                "DELETE FROM document_versions WHERE document_id IN"
                        + " (SELECT id FROM documents WHERE work_case_id = ?)",
                fixture.workCaseId());
        jdbcTemplate.update(
                "DELETE FROM documents WHERE work_case_id = ?",
                fixture.workCaseId());
        jdbcTemplate.update(
                "DELETE FROM settlements WHERE work_case_id = ?",
                fixture.workCaseId());
        jdbcTemplate.update(
                "DELETE FROM escrows WHERE work_case_id = ?",
                fixture.workCaseId());
        jdbcTemplate.update(
                "DELETE FROM work_contracts WHERE work_case_id = ?",
                fixture.workCaseId());
        jdbcTemplate.update(
                "DELETE FROM work_invitations WHERE work_case_id = ?",
                fixture.workCaseId());
        jdbcTemplate.update("DELETE FROM work_cases WHERE id = ?", fixture.workCaseId());
        jdbcTemplate.update("DELETE FROM workplaces WHERE id = ?", fixture.workplaceId());
        jdbcTemplate.update(
                "DELETE FROM wallets WHERE id IN (?, ?)",
                fixture.ownerWalletId(),
                fixture.workerWalletId());
        jdbcTemplate.update(
                "DELETE FROM users WHERE id IN (?, ?)",
                fixture.ownerId(),
                fixture.workerId());
        deleteStoredContract(context, fixture.workCaseId());
    }

    private void deleteStoredContract(
            AnnotationConfigApplicationContext context,
            long workCaseId) {
        Path basePath = context.getBean(DocumentStorageProperties.class).getBasePath();
        Path workCasePath = basePath.resolve("contracts").resolve(Long.toString(workCaseId))
                .normalize();
        if (!workCasePath.startsWith(basePath) || !Files.exists(workCasePath)) {
            return;
        }
        try (var paths = Files.walk(workCasePath)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("테스트 계약 파일을 정리하지 못했습니다.", e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("테스트 계약 경로를 정리하지 못했습니다.", e);
        }
    }

    private long idBy(
            JdbcTemplate jdbcTemplate,
            String table,
            String column,
            Object value) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM " + table + " WHERE " + column + " = ?",
                Long.class,
                value);
        if (id == null) {
            throw new IllegalStateException("통합 테스트 fixture ID를 찾을 수 없습니다.");
        }
        return id;
    }

    private long number(JdbcTemplate jdbcTemplate, String sql, Object value) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class, value);
        return result == null ? 0L : result;
    }

    private String text(JdbcTemplate jdbcTemplate, String sql, Object value) {
        String result = jdbcTemplate.queryForObject(sql, String.class, value);
        if (result == null) {
            throw new IllegalStateException("통합 테스트 상태 값을 찾을 수 없습니다.");
        }
        return result;
    }

    private record Fixture(
            long ownerId,
            long workerId,
            long ownerWalletId,
            long workerWalletId,
            long workplaceId,
            long workCaseId) {
    }

    private record FundsSnapshot(
            long ownerAvailable,
            long ownerLocked,
            long workerAvailable,
            String escrowStatus,
            String settlementStatus,
            LocalDateTime settlementDueAt,
            long ledgerCount) {
    }
}
