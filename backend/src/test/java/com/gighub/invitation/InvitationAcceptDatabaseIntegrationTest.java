package com.gighub.invitation;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.ConflictException;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.config.RootConfig;
import com.gighub.contract.ContractArtifactCommand;
import com.gighub.contract.ContractArtifactHandle;
import com.gighub.contract.ContractArtifactPort;
import com.gighub.document.service.DocumentFileAccessService;
import com.gighub.document.service.DocumentFileResult;
import com.gighub.document.storage.ContractStorageKeys;
import com.gighub.document.storage.DocumentStorageAdapter;
import com.gighub.document.storage.DocumentStorageIntegrityException;
import com.gighub.document.storage.LocalFileDocumentStorageAdapter;
import com.gighub.document.storage.Sha256;
import com.gighub.idempotency.exception.IdempotencyClaimKeyReusedException;
import com.gighub.idempotency.IdempotencyClaimService;
import com.gighub.invitation.application.InvitationAcceptanceCommand;
import com.gighub.invitation.application.InvitationAcceptanceOrchestrator;
import com.gighub.invitation.application.InvitationAcceptanceOutcome;
import com.gighub.invitation.application.InvitationAcceptanceReplaySnapshotCodec;
import com.gighub.invitation.exception.InvitationAlreadyAcceptedException;
import com.gighub.invitation.exception.InvitationExpiredException;
import com.gighub.invitation.service.AcceptanceWorkParticipant;
import com.gighub.notification.service.NotificationRecorder;
import com.gighub.invitation.service.InvitationAcceptResult;
import com.gighub.invitation.service.InvitationAcceptService;
import com.gighub.invitation.service.InvitationIssueService;
import com.gighub.member.domain.UserRole;
import com.gighub.document.storage.DocumentStorageProperties;
import com.gighub.settlement.service.SettlementReservationService;
import com.gighub.wallet.service.AcceptEscrowHold;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 MySQL에서 수락 Aggregate가 전부 반영되거나 전부 사라지는지 검증합니다.
 *
 * <p>여섯 Aggregate의 원자성, 같은 Key Replay가 자금을 다시 움직이지 않는 것, 같은 Token을
 * 여러 WORKER가 동시에 수락할 때 한 명만 이기는 것은 실제 Transaction과 행 잠금 없이는
 * 확인할 수 없어 {@code database} Tag의 Opt-in Test로 둡니다.</p>
 */
@Tag("database")
class InvitationAcceptDatabaseIntegrationTest {

    private static final long WAGE = 120_000L;
    private static final long INITIAL_AVAILABLE = 500_000L;

    @Test
    @Timeout(120)
    void acceptCommitsEveryAggregateOrLeavesNothingBehind() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            InvitationIssueService issueService = context.getBean(InvitationIssueService.class);
            InvitationAcceptService acceptService = context.getBean(InvitationAcceptService.class);
            DocumentFileAccessService fileAccessService =
                    context.getBean(DocumentFileAccessService.class);
            Path storageBasePath = context.getBean(DocumentStorageProperties.class).getBasePath();

            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            Fixture fixture = insertFixture(jdbcTemplate, suffix, INITIAL_AVAILABLE);

            try {
                verifyOwnerCannotAcceptOwnWorkCase(issueService, acceptService, fixture);
                verifyInsufficientBalanceLeavesNothing(
                        jdbcTemplate, issueService, acceptService, fixture);
                verifyAcceptWritesEveryAggregate(
                        jdbcTemplate, issueService, acceptService, fileAccessService,
                        storageBasePath, fixture);
                verifyReplayDoesNotMoveMoneyAgain(jdbcTemplate, acceptService, fixture);
                verifyReusedKeyOnAnotherTokenIsRejected(issueService, acceptService, fixture);
            } finally {
                cleanUp(jdbcTemplate, fixture, storageBasePath);
            }
        }
    }

    @Test
    @Timeout(120)
    void concurrentWorkersOnTheSameTokenLeaveExactlyOneWinner() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            InvitationIssueService issueService = context.getBean(InvitationIssueService.class);
            InvitationAcceptService acceptService = context.getBean(InvitationAcceptService.class);
            Path storageBasePath = context.getBean(DocumentStorageProperties.class).getBasePath();

            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            Fixture fixture = insertFixture(jdbcTemplate, suffix, INITIAL_AVAILABLE);
            String token = issueToken(issueService, fixture);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Boolean>> results = List.of(
                        executor.submit(attempt(acceptService, fixture.worker(), token, start)),
                        executor.submit(attempt(acceptService, fixture.otherWorker(), token, start))
                );

                start.countDown();

                int winners = 0;
                for (Future<Boolean> result : results) {
                    if (Boolean.TRUE.equals(result.get(60L, TimeUnit.SECONDS))) {
                        winners++;
                    }
                }

                assertEquals(1, winners, "같은 Token은 한 WORKER만 수락할 수 있습니다.");
                assertEquals(
                        1,
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM work_contracts WHERE work_case_id = ?",
                                Integer.class,
                                fixture.workCaseId)
                );
                assertEquals(1, countRows(jdbcTemplate, "documents", fixture));
                assertEquals(2, countDocumentRows(jdbcTemplate, "document_versions", fixture));
                assertEquals(1, countDocumentRows(jdbcTemplate, "document_signatures", fixture));
                assertEquals(1, countDocumentRows(jdbcTemplate, "document_shares", fixture));
                // 진 쪽의 예치가 남으면 사장님 잔액이 두 번 잠깁니다.
                assertEquals(
                        INITIAL_AVAILABLE - WAGE,
                        availableBalance(jdbcTemplate, fixture.ownerUserId)
                );
            } finally {
                executor.shutdownNow();
                cleanUp(jdbcTemplate, fixture, storageBasePath);
            }
        }
    }

    @Test
    @Timeout(120)
    void expiredAcceptCommitsExpiryAcrossMandatoryParticipantBoundary() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            InvitationIssueService issueService = context.getBean(InvitationIssueService.class);
            InvitationAcceptService acceptService = context.getBean(InvitationAcceptService.class);
            Path storageBasePath = context.getBean(DocumentStorageProperties.class).getBasePath();

            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            Fixture fixture = insertFixture(jdbcTemplate, suffix, INITIAL_AVAILABLE);
            String token = issueToken(issueService, fixture);

            try {
                jdbcTemplate.update(
                        "UPDATE work_invitations SET expires_at = ?"
                                + " WHERE work_case_id = ? AND status = 'PENDING'",
                        LocalDateTime.now().minusMinutes(1L),
                        fixture.workCaseId);
                Long expiredInvitationId = jdbcTemplate.queryForObject(
                        "SELECT id FROM work_invitations"
                                + " WHERE work_case_id = ? AND status = 'PENDING'",
                        Long.class,
                        fixture.workCaseId);

                assertThrows(
                        InvitationExpiredException.class,
                        () -> acceptService.accept(
                                fixture.worker(), token, "expired-" + fixture.suffix)
                );

                assertEquals(
                        "EXPIRED",
                        jdbcTemplate.queryForObject(
                                "SELECT status FROM work_invitations WHERE id = ?",
                                String.class,
                                expiredInvitationId)
                );
                assertEquals("DRAFT", workCaseStatus(jdbcTemplate, fixture));
                assertEquals(0, countRows(jdbcTemplate, "work_contracts", fixture));
                assertEquals(0, countRows(jdbcTemplate, "escrows", fixture));
                assertEquals(0, countRows(jdbcTemplate, "settlements", fixture));
                assertEquals(0, countRows(jdbcTemplate, "wallet_transactions", fixture));
                assertEquals(0, countClaims(jdbcTemplate, fixture.workerUserId));
                assertEquals(INITIAL_AVAILABLE,
                        availableBalance(jdbcTemplate, fixture.ownerUserId));
                assertEquals(0L, lockedBalance(jdbcTemplate, fixture.ownerUserId));

                assertNotNull(issueToken(issueService, fixture));
                assertEquals(
                        1,
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM work_invitations"
                                        + " WHERE work_case_id = ? AND status = 'PENDING'",
                                Integer.class,
                                fixture.workCaseId)
                );
            } finally {
                cleanUp(jdbcTemplate, fixture, storageBasePath);
            }
        }
    }

    @Test
    @Timeout(120)
    void documentPrepareFailureRollsBackEveryEarlierParticipant() throws Exception {
        ContractArtifactPort failingDocument = new ContractArtifactPort() {
            @Override
            public ContractArtifactHandle prepare(ContractArtifactCommand command) {
                throw new IllegalStateException("document failure injection");
            }

            @Override
            public void promote(ContractArtifactHandle handle) {
            }

            @Override
            public void discardPending(long workCaseId) {
            }
        };
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(RootConfig.class);
            context.registerBean(
                    "failingAcceptanceDocumentParticipant",
                    ContractArtifactPort.class,
                    () -> failingDocument,
                    definition -> definition.setPrimary(true));
            context.refresh();

            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            Path storageBasePath = context.getBean(DocumentStorageProperties.class).getBasePath();
            Fixture fixture = insertFixture(
                    jdbcTemplate,
                    UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                    INITIAL_AVAILABLE);
            try {
                String token = issueToken(context.getBean(InvitationIssueService.class), fixture);
                assertThrows(
                        IllegalStateException.class,
                        () -> context.getBean(InvitationAcceptService.class).accept(
                                fixture.worker(), token, "document-fail-" + fixture.suffix));
                assertFailedAcceptanceLeavesNothing(jdbcTemplate, fixture);
            } finally {
                cleanUp(jdbcTemplate, fixture, storageBasePath);
            }
        }
    }

    @Test
    @Timeout(120)
    void transientFailureAfterEveryParticipantRetriesTheWholeAggregateInANewTransaction()
            throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(RootConfig.class);
            context.registerBean(
                    "retryInjectingInvitationAcceptanceOrchestrator",
                    InvitationAcceptanceOrchestrator.class,
                    () -> new InvitationAcceptanceOrchestrator(
                            context.getBean(AcceptanceWorkParticipant.class),
                            context.getBean(AcceptEscrowHold.class),
                            context.getBean(SettlementReservationService.class),
                            context.getBean(IdempotencyClaimService.class),
                            context.getBean(InvitationAcceptanceReplaySnapshotCodec.class),
                            context.getBean(ContractArtifactPort.class),
                            context.getBean(NotificationRecorder.class)) {
                        @Override
                        @Transactional(
                                propagation = Propagation.REQUIRES_NEW,
                                noRollbackFor = InvitationExpiredException.class)
                        public InvitationAcceptanceOutcome execute(
                                InvitationAcceptanceCommand command) {
                            InvitationAcceptanceOutcome outcome = super.execute(command);
                            if (attempts.incrementAndGet() == 1) {
                                throw new CannotAcquireLockException(
                                        "transient failure after every participant");
                            }
                            return outcome;
                        }
                    },
                    definition -> definition.setPrimary(true));
            context.refresh();

            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            DocumentFileAccessService fileAccessService =
                    context.getBean(DocumentFileAccessService.class);
            Path storageBasePath = context.getBean(DocumentStorageProperties.class).getBasePath();
            Fixture fixture = insertFixture(
                    jdbcTemplate,
                    UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                    INITIAL_AVAILABLE);
            try {
                verifyAcceptWritesEveryAggregate(
                        jdbcTemplate,
                        context.getBean(InvitationIssueService.class),
                        context.getBean(InvitationAcceptService.class),
                        fileAccessService,
                        storageBasePath,
                        fixture);
                assertEquals(2, attempts.get());
                assertEquals(1, countCompletedClaims(jdbcTemplate, fixture.workerUserId));
            } finally {
                cleanUp(jdbcTemplate, fixture, storageBasePath);
            }
        }
    }

    @Test
    @Timeout(120)
    void settlementFailureRollsBackDocumentFilesAndEveryEarlierParticipant() throws Exception {
        SettlementReservationService failingSettlement = new SettlementReservationService() {
            @Override
            public void reserveWaiting(long workCaseId, long amount) {
                throw new IllegalStateException("settlement failure injection");
            }

            @Override
            public void schedulePayout(long workCaseId, java.time.LocalDateTime dueAt) {
                throw new UnsupportedOperationException();
            }
        };
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(RootConfig.class);
            context.registerBean(
                    "failingAcceptanceSettlementParticipant",
                    SettlementReservationService.class,
                    () -> failingSettlement,
                    definition -> definition.setPrimary(true));
            context.refresh();

            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            Path storageBasePath = context.getBean(DocumentStorageProperties.class).getBasePath();
            Fixture fixture = insertFixture(
                    jdbcTemplate,
                    UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                    INITIAL_AVAILABLE);
            try {
                String token = issueToken(context.getBean(InvitationIssueService.class), fixture);
                assertThrows(
                        IllegalStateException.class,
                        () -> context.getBean(InvitationAcceptService.class).accept(
                                fixture.worker(), token, "settlement-fail-" + fixture.suffix));
                assertFailedAcceptanceLeavesNothing(jdbcTemplate, fixture);
                assertFalse(Files.exists(storageBasePath
                        .resolve("contracts")
                        .resolve(Long.toString(fixture.workCaseId))));
            } finally {
                cleanUp(jdbcTemplate, fixture, storageBasePath);
            }
        }
    }

    @Test
    @Timeout(120)
    void promotionFailureKeepsCommittedSuccessPendingFallbackAndExactReplay() throws Exception {
        AtomicReference<PromotionFailingStorageAdapter> storageRef = new AtomicReference<>();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(RootConfig.class);
            context.registerBean(
                    "promotionFailingDocumentStorageAdapter",
                    DocumentStorageAdapter.class,
                    () -> {
                        PromotionFailingStorageAdapter adapter =
                                new PromotionFailingStorageAdapter(
                                        new LocalFileDocumentStorageAdapter(
                                                context.getBean(DocumentStorageProperties.class)));
                        storageRef.set(adapter);
                        return adapter;
                    },
                    definition -> definition.setPrimary(true));
            context.refresh();

            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            InvitationIssueService issueService = context.getBean(InvitationIssueService.class);
            InvitationAcceptService acceptService = context.getBean(InvitationAcceptService.class);
            DocumentFileAccessService fileAccessService =
                    context.getBean(DocumentFileAccessService.class);
            Path storageBasePath = context.getBean(DocumentStorageProperties.class).getBasePath();
            Fixture fixture = insertFixture(
                    jdbcTemplate,
                    UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                    INITIAL_AVAILABLE);
            try {
                String token = issueToken(issueService, fixture);
                InvitationAcceptResult first = acceptService.accept(
                        fixture.worker(), token, fixture.acceptKey());
                assertFalse(first.isReplayed());
                assertEquals(fixture.workCaseId, first.getResult().getWorkCaseId());
                assertEquals(1, countCompletedClaims(jdbcTemplate, fixture.workerUserId));

                long documentId = jdbcTemplate.queryForObject(
                        "SELECT id FROM documents WHERE work_case_id = ?",
                        Long.class,
                        fixture.workCaseId);
                List<Map<String, Object>> versions = jdbcTemplate.queryForList(
                        "SELECT version_no, storage_key, checksum FROM document_versions"
                                + " WHERE document_id = ? ORDER BY version_no",
                        documentId);
                assertEquals(2, versions.size());
                for (Map<String, Object> version : versions) {
                    int versionNo = ((Number) version.get("version_no")).intValue();
                    assertFalse(Files.exists(storageBasePath.resolve(
                            version.get("storage_key").toString())));
                    assertTrue(Files.exists(storageBasePath.resolve(
                            ContractStorageKeys.pendingKey(
                                    fixture.workCaseId, documentId, versionNo))));
                }

                DocumentFileResult fallback =
                        fileAccessService.loadFile(
                                documentId,
                                fixture.workerUserId,
                                UserRole.WORKER,
                                "download");
                assertArrayEquals(
                        (byte[]) versions.get(1).get("checksum"),
                        Sha256.digest(fallback.getContent()));

                int promotionAttemptsBeforeReplay = storageRef.get().promotionAttempts.get();
                InvitationAcceptResult replay = acceptService.accept(
                        fixture.worker(), token, fixture.acceptKey());
                assertTrue(replay.isReplayed());
                assertEquals(first.getResult().getWorkCaseId(), replay.getResult().getWorkCaseId());
                assertEquals(first.getResult().getEscrowStatus(), replay.getResult().getEscrowStatus());
                assertEquals(promotionAttemptsBeforeReplay, storageRef.get().promotionAttempts.get());
                assertEquals(1, countRows(jdbcTemplate, "wallet_transactions", fixture));
                assertEquals(1, countRows(jdbcTemplate, "work_contracts", fixture));
                assertEquals(1, countRows(jdbcTemplate, "settlements", fixture));
            } finally {
                cleanUp(jdbcTemplate, fixture, storageBasePath);
            }
        }
    }

    /**
     * OWNER 계정은 역할 검사에서 먼저 걸립니다.
     *
     * <p>당사자 검사({@code 403 FORBIDDEN})는 그 뒤 Transaction 안에 있어, 역할이 WORKER인데
     * 같은 근무의 OWNER이기도 한 어긋난 데이터에서만 도달합니다. 그 방어선은
     * {@code InvitationAcceptanceOrchestratorTest}가 확인합니다.</p>
     */
    private void verifyOwnerCannotAcceptOwnWorkCase(
            InvitationIssueService issueService,
            InvitationAcceptService acceptService,
            Fixture fixture) {
        String token = issueToken(issueService, fixture);

        assertThrows(
                RoleMismatchException.class,
                () -> acceptService.accept(fixture.owner(), token, "owner-" + fixture.suffix)
        );
    }

    private void assertFailedAcceptanceLeavesNothing(
            JdbcTemplate jdbcTemplate, Fixture fixture) {
        assertEquals("DRAFT", workCaseStatus(jdbcTemplate, fixture));
        assertEquals(
                "PENDING",
                jdbcTemplate.queryForObject(
                        "SELECT status FROM work_invitations WHERE work_case_id = ?"
                                + " ORDER BY id DESC LIMIT 1",
                        String.class,
                        fixture.workCaseId));
        assertEquals(0, countRows(jdbcTemplate, "work_contracts", fixture));
        assertEquals(0, countRows(jdbcTemplate, "escrows", fixture));
        assertEquals(0, countRows(jdbcTemplate, "settlements", fixture));
        assertEquals(0, countRows(jdbcTemplate, "wallet_transactions", fixture));
        assertEquals(0, countRows(jdbcTemplate, "documents", fixture));
        assertEquals(INITIAL_AVAILABLE, availableBalance(jdbcTemplate, fixture.ownerUserId));
        assertEquals(0L, lockedBalance(jdbcTemplate, fixture.ownerUserId));
        assertEquals(0, countClaims(jdbcTemplate, fixture.workerUserId));
    }

    /** 잔액이 모자라면 앞 단계의 매칭·계약까지 모두 사라져야 합니다. */
    private void verifyInsufficientBalanceLeavesNothing(
            JdbcTemplate jdbcTemplate,
            InvitationIssueService issueService,
            InvitationAcceptService acceptService,
            Fixture fixture) {
        jdbcTemplate.update(
                "UPDATE wallets SET available_balance = ? WHERE user_id = ?",
                WAGE - 1L,
                fixture.ownerUserId);
        String token = issueToken(issueService, fixture);

        assertThrows(
                ConflictException.class,
                () -> acceptService.accept(fixture.worker(), token, "poor-" + fixture.suffix)
        );

        assertEquals("DRAFT", workCaseStatus(jdbcTemplate, fixture));
        assertEquals(0, countRows(jdbcTemplate, "work_contracts", fixture));
        assertEquals(0, countRows(jdbcTemplate, "escrows", fixture));
        assertEquals(0, countRows(jdbcTemplate, "settlements", fixture));
        assertEquals(0, countRows(jdbcTemplate, "wallet_transactions", fixture));
        assertEquals(
                "PENDING",
                jdbcTemplate.queryForObject(
                        "SELECT status FROM work_invitations WHERE work_case_id = ?"
                                + " ORDER BY id DESC LIMIT 1",
                        String.class,
                        fixture.workCaseId)
        );
        // 실패한 Claim이 남으면 같은 Key로 다시 시도할 수 없습니다.
        assertEquals(0, countClaims(jdbcTemplate, fixture.workerUserId));

        jdbcTemplate.update(
                "UPDATE wallets SET available_balance = ? WHERE user_id = ?",
                INITIAL_AVAILABLE,
                fixture.ownerUserId);
    }

    /** 정상 수락은 여섯 Aggregate를 하나의 시각으로 남깁니다. */
    private void verifyAcceptWritesEveryAggregate(
            JdbcTemplate jdbcTemplate,
            InvitationIssueService issueService,
            InvitationAcceptService acceptService,
            DocumentFileAccessService fileAccessService,
            Path storageBasePath,
            Fixture fixture) {
        String token = issueToken(issueService, fixture);

        InvitationAcceptResult result =
                acceptService.accept(fixture.worker(), token, fixture.acceptKey());

        assertFalse(result.isReplayed());
        assertEquals(fixture.workCaseId, result.getResult().getWorkCaseId());
        assertEquals("HELD", result.getResult().getEscrowStatus());

        Map<String, Object> workCase = jdbcTemplate.queryForMap(
                "SELECT status, worker_id, terms_version FROM work_cases WHERE id = ?",
                fixture.workCaseId);
        assertEquals("ACCEPTED", workCase.get("status"));
        assertEquals(
                fixture.workerUserId, ((Number) workCase.get("worker_id")).longValue());

        Map<String, Object> invitation = jdbcTemplate.queryForMap(
                "SELECT status, accepted_by_user_id, accepted_terms_version, accepted_at"
                        + " FROM work_invitations WHERE work_case_id = ? AND status = 'ACCEPTED'",
                fixture.workCaseId);
        assertEquals(
                fixture.workerUserId,
                ((Number) invitation.get("accepted_by_user_id")).longValue());
        assertEquals(
                ((Number) workCase.get("terms_version")).intValue(),
                ((Number) invitation.get("accepted_terms_version")).intValue());

        Map<String, Object> contract = jdbcTemplate.queryForMap(
                "SELECT employer_id, worker_id, agreed_wage, source_terms_version,"
                        + " accepted_at, terms_snapshot FROM work_contracts WHERE work_case_id = ?",
                fixture.workCaseId);
        assertEquals(WAGE, ((Number) contract.get("agreed_wage")).longValue());
        assertNotNull(contract.get("terms_snapshot"));

        Map<String, Object> document = jdbcTemplate.queryForMap(
                "SELECT id, owner_user_id, status FROM documents"
                        + " WHERE work_case_id = ? AND document_type = 'EMPLOYMENT_CONTRACT'",
                fixture.workCaseId);
        long documentId = ((Number) document.get("id")).longValue();
        assertEquals(fixture.ownerUserId, ((Number) document.get("owner_user_id")).longValue());
        assertEquals("ACTIVE", document.get("status"));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_versions WHERE document_id = ?",
                Integer.class, documentId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_signatures WHERE document_id = ?",
                Integer.class, documentId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_shares"
                        + " WHERE document_id = ? AND shared_with_user_id = ?"
                        + " AND purpose = 'CONTRACT_PARTY' AND status = 'ACTIVE'",
                Integer.class, documentId, fixture.workerUserId));

        List<Map<String, Object>> versions = jdbcTemplate.queryForList(
                "SELECT id, version_no, version_type, storage_key, size_bytes, checksum"
                        + " FROM document_versions WHERE document_id = ? ORDER BY version_no",
                documentId);
        assertEquals(2, versions.size());
        assertEquals("ORIGINAL", versions.get(0).get("version_type"));
        assertEquals("SIGNED", versions.get(1).get("version_type"));
        assertEquals(
                ContractStorageKeys.finalKey(fixture.workCaseId, documentId, 1),
                versions.get(0).get("storage_key"));
        assertEquals(
                ContractStorageKeys.finalKey(fixture.workCaseId, documentId, 2),
                versions.get(1).get("storage_key"));
        assertTrue(Files.exists(storageBasePath.resolve(
                versions.get(0).get("storage_key").toString())));
        assertTrue(Files.exists(storageBasePath.resolve(
                versions.get(1).get("storage_key").toString())));

        Map<String, Object> signature = jdbcTemplate.queryForMap(
                "SELECT source_version_id, signed_version_id, signer_user_id,"
                        + " source_checksum, signed_checksum, typed_name, signature_method,"
                        + " consented_at, signed_at FROM document_signatures"
                        + " WHERE document_id = ?",
                documentId);
        assertEquals(
                ((Number) versions.get(0).get("id")).longValue(),
                ((Number) signature.get("source_version_id")).longValue());
        assertEquals(
                ((Number) versions.get(1).get("id")).longValue(),
                ((Number) signature.get("signed_version_id")).longValue());
        assertEquals(fixture.workerUserId,
                ((Number) signature.get("signer_user_id")).longValue());
        assertEquals("수락검증알바", signature.get("typed_name"));
        assertEquals("TYPED_NAME", signature.get("signature_method"));
        assertArrayEquals((byte[]) versions.get(0).get("checksum"),
                (byte[]) signature.get("source_checksum"));
        assertArrayEquals((byte[]) versions.get(1).get("checksum"),
                (byte[]) signature.get("signed_checksum"));

        DocumentFileResult ownerFile =
                fileAccessService.loadFile(
                        documentId, fixture.ownerUserId, UserRole.OWNER, "view");
        DocumentFileResult workerFile =
                fileAccessService.loadFile(
                        documentId, fixture.workerUserId, UserRole.WORKER, "download");
        assertArrayEquals(ownerFile.getContent(), workerFile.getContent());
        assertArrayEquals((byte[]) versions.get(1).get("checksum"),
                Sha256.digest(ownerFile.getContent()));
        assertDocumentUniqueness(jdbcTemplate, documentId,
                ((Number) versions.get(0).get("id")).longValue());

        // 계약·에스크로·초대가 모두 같은 순간을 가리켜야 합니다.
        LocalDateTime acceptedAt = toLocalDateTime(contract.get("accepted_at"));
        assertEquals(acceptedAt, toLocalDateTime(invitation.get("accepted_at")));
        assertEquals(
                acceptedAt,
                toLocalDateTime(jdbcTemplate.queryForMap(
                        "SELECT held_at FROM escrows WHERE work_case_id = ?",
                        fixture.workCaseId).get("held_at"))
        );

        assertEquals(INITIAL_AVAILABLE - WAGE, availableBalance(jdbcTemplate, fixture.ownerUserId));
        assertEquals(WAGE, lockedBalance(jdbcTemplate, fixture.ownerUserId));

        Map<String, Object> ledger = jdbcTemplate.queryForMap(
                "SELECT transaction_type, amount, available_before, available_after,"
                        + " locked_before, locked_after, reference_type"
                        + " FROM wallet_transactions WHERE work_case_id = ?",
                fixture.workCaseId);
        assertEquals("ESCROW_HOLD", ledger.get("transaction_type"));
        assertEquals(INITIAL_AVAILABLE, ((Number) ledger.get("available_before")).longValue());
        assertEquals(
                INITIAL_AVAILABLE - WAGE, ((Number) ledger.get("available_after")).longValue());
        assertEquals(WAGE, ((Number) ledger.get("locked_after")).longValue());
        assertEquals("ESCROW", ledger.get("reference_type"));

        assertEquals(
                "WAITING",
                jdbcTemplate.queryForObject(
                        "SELECT status FROM settlements WHERE work_case_id = ?",
                        String.class,
                        fixture.workCaseId)
        );
        assertEquals(1, countCompletedClaims(jdbcTemplate, fixture.workerUserId));
    }

    /** 저장된 결과 재생은 자금을 다시 움직이지 않습니다. */
    private void verifyReplayDoesNotMoveMoneyAgain(
            JdbcTemplate jdbcTemplate,
            InvitationAcceptService acceptService,
            Fixture fixture) {
        long availableBefore = availableBalance(jdbcTemplate, fixture.ownerUserId);

        InvitationAcceptResult replay = acceptService.accept(
                fixture.worker(), fixture.acceptedToken, fixture.acceptKey());

        assertTrue(replay.isReplayed());
        assertEquals(fixture.workCaseId, replay.getResult().getWorkCaseId());
        assertEquals("HELD", replay.getResult().getEscrowStatus());

        assertEquals(availableBefore, availableBalance(jdbcTemplate, fixture.ownerUserId));
        assertEquals(1, countRows(jdbcTemplate, "wallet_transactions", fixture));
        assertEquals(1, countRows(jdbcTemplate, "work_contracts", fixture));
        assertEquals(1, countRows(jdbcTemplate, "settlements", fixture));
    }

    /** 같은 Key를 다른 Token에 쓰면 재사용으로 거절해야 합니다. */
    private void verifyReusedKeyOnAnotherTokenIsRejected(
            InvitationIssueService issueService,
            InvitationAcceptService acceptService,
            Fixture fixture) {
        String otherToken = tokenOf(issueService.issue(fixture.owner(), fixture.otherWorkCaseId)
                .getResponse()
                .getInviteUrl());

        assertThrows(
                IdempotencyClaimKeyReusedException.class,
                () -> acceptService.accept(fixture.worker(), otherToken, fixture.acceptKey())
        );
    }

    private Callable<Boolean> attempt(
            InvitationAcceptService acceptService,
            AuthPrincipal principal,
            String token,
            CountDownLatch start) {
        return () -> {
            start.await();
            try {
                acceptService.accept(principal, token, "race-" + principal.getUserId());
                return true;
            } catch (InvitationAlreadyAcceptedException | ConflictException expected) {
                return false;
            }
        };
    }

    private String issueToken(InvitationIssueService issueService, Fixture fixture) {
        String token = tokenOf(issueService.issue(fixture.owner(), fixture.workCaseId)
                .getResponse()
                .getInviteUrl());
        fixture.acceptedToken = token;
        return token;
    }

    private static String tokenOf(String inviteUrl) {
        return inviteUrl.substring(inviteUrl.lastIndexOf(0x2f) + 1);
    }

    private String workCaseStatus(JdbcTemplate jdbcTemplate, Fixture fixture) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM work_cases WHERE id = ?", String.class, fixture.workCaseId);
    }

    private int countRows(JdbcTemplate jdbcTemplate, String table, Fixture fixture) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE work_case_id = ?",
                Integer.class,
                fixture.workCaseId);
    }

    private int countClaims(JdbcTemplate jdbcTemplate, long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_requests WHERE user_id = ?",
                Integer.class,
                userId);
    }

    private int countCompletedClaims(JdbcTemplate jdbcTemplate, long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_requests"
                        + " WHERE user_id = ? AND status = 'COMPLETED'",
                Integer.class,
                userId);
    }

    private long availableBalance(JdbcTemplate jdbcTemplate, long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT available_balance FROM wallets WHERE user_id = ?", Long.class, userId);
    }

    private long lockedBalance(JdbcTemplate jdbcTemplate, long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT locked_balance FROM wallets WHERE user_id = ?", Long.class, userId);
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        return value instanceof LocalDateTime
                ? (LocalDateTime) value
                : ((java.sql.Timestamp) value).toLocalDateTime();
    }

    private static final class PromotionFailingStorageAdapter
            implements DocumentStorageAdapter {

        private final DocumentStorageAdapter delegate;
        private final AtomicInteger promotionAttempts = new AtomicInteger();

        private PromotionFailingStorageAdapter(DocumentStorageAdapter delegate) {
            this.delegate = delegate;
        }

        @Override
        public void writePending(String pendingKey, byte[] content) {
            delegate.writePending(pendingKey, content);
        }

        @Override
        public void promote(String pendingKey, String finalKey, byte[] expectedSha256) {
            promotionAttempts.incrementAndGet();
            throw new DocumentStorageIntegrityException("promotion failure injection");
        }

        @Override
        public byte[] read(String key) {
            return delegate.read(key);
        }

        @Override
        public boolean exists(String key) {
            return delegate.exists(key);
        }

        @Override
        public void deletePending(String pendingKey) {
            delegate.deletePending(pendingKey);
        }

        @Override
        public void deletePendingByWorkCaseId(
                long workCaseId, Set<Long> retainedDocumentIds) {
            delegate.deletePendingByWorkCaseId(workCaseId, retainedDocumentIds);
        }

        @Override
        public void deleteFinal(String finalKey) {
            delegate.deleteFinal(finalKey);
        }
    }

    private Fixture insertFixture(JdbcTemplate jdbcTemplate, String suffix, long available) {
        long ownerUserId = insertUser(jdbcTemplate, "qa156ao" + suffix, "OWNER", "수락검증사장");
        long workerUserId = insertUser(jdbcTemplate, "qa156aw" + suffix, "WORKER", "수락검증알바");
        long otherWorkerId = insertUser(jdbcTemplate, "qa156ax" + suffix, "WORKER", "수락검증알바2");

        jdbcTemplate.update(
                "INSERT INTO wallets (user_id, available_balance, locked_balance)"
                        + " VALUES (?, ?, 0)",
                ownerUserId,
                available);

        String businessNumber = String.format(
                "%010d", ThreadLocalRandom.current().nextLong(1_000_000_0000L));
        jdbcTemplate.update(
                "INSERT INTO workplaces (owner_user_id, business_registration_number, name,"
                        + " representative_name, road_address, phone)"
                        + " VALUES (?, ?, '강남점', '김사장', '서울 강남구 테헤란로 1', '0212345678')",
                ownerUserId,
                businessNumber);
        long workplaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM workplaces WHERE business_registration_number = ?",
                Long.class,
                businessNumber);

        LocalDateTime startsAt = LocalDateTime.now().plusDays(30L).truncatedTo(ChronoUnit.MICROS);
        long workCaseId = insertWorkCase(jdbcTemplate, ownerUserId, workplaceId, startsAt);
        long otherWorkCaseId =
                insertWorkCase(jdbcTemplate, ownerUserId, workplaceId, startsAt.plusDays(1L));

        Fixture fixture = new Fixture(
                suffix, ownerUserId, workerUserId, otherWorkerId, workplaceId,
                workCaseId, otherWorkCaseId);
        // 같은 Key 재사용 검증에 쓸 다른 근무의 Token입니다.
        fixture.otherToken = null;
        return fixture;
    }

    private long insertWorkCase(
            JdbcTemplate jdbcTemplate, long ownerUserId, long workplaceId, LocalDateTime startsAt) {
        jdbcTemplate.update(
                "INSERT INTO work_cases (employer_id, workplace_id, title, starts_at, ends_at,"
                        + " break_minutes, break_paid, workplace_name, workplace_address,"
                        + " workplace_latitude, workplace_longitude, agreed_wage, terms_version,"
                        + " status)"
                        + " VALUES (?, ?, '주말 홀 서빙', ?, ?, 60, 0, '강남점',"
                        + " '서울 강남구 테헤란로 1', 37.4980000, 127.0270000, ?, 1, 'DRAFT')",
                ownerUserId,
                workplaceId,
                startsAt,
                startsAt.plusHours(8L),
                WAGE);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM work_cases WHERE employer_id = ? AND starts_at = ?",
                Long.class,
                ownerUserId,
                startsAt);
    }

    private long insertUser(
            JdbcTemplate jdbcTemplate, String loginId, String role, String name) {
        jdbcTemplate.update(
                "INSERT INTO users (login_id, email, password_hash, name, role)"
                        + " VALUES (?, ?, ?, ?, ?)",
                loginId,
                loginId + "@example.com",
                "$2a$10$abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQR",
                name,
                role);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE login_id = ?", Long.class, loginId);
    }

    private int countDocumentRows(
            JdbcTemplate jdbcTemplate, String tableName, Fixture fixture) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName
                        + " WHERE document_id IN (SELECT id FROM documents WHERE work_case_id = ?)",
                Integer.class, fixture.workCaseId);
    }

    private void assertDocumentUniqueness(
            JdbcTemplate jdbcTemplate, long documentId, long originalVersionId) {
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO documents"
                        + " (created_by_user_id, owner_user_id, work_case_id, document_type,"
                        + " status, issued_on)"
                        + " SELECT created_by_user_id, owner_user_id, work_case_id, document_type,"
                        + " status, issued_on FROM documents WHERE id = ?",
                documentId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO document_versions"
                        + " (document_id, version_no, version_type, storage_key, mime_type,"
                        + " size_bytes, checksum)"
                        + " SELECT document_id, version_no, version_type, CONCAT(storage_key, '.dup'),"
                        + " mime_type, size_bytes, checksum FROM document_versions WHERE id = ?",
                originalVersionId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO document_signatures"
                        + " (document_id, source_version_id, signed_version_id, signer_user_id,"
                        + " source_checksum, signed_checksum, typed_name, signature_method,"
                        + " consented_at, signed_at)"
                        + " SELECT document_id, source_version_id, signed_version_id, signer_user_id,"
                        + " source_checksum, signed_checksum, typed_name, signature_method,"
                        + " consented_at, signed_at FROM document_signatures WHERE document_id = ?",
                documentId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO document_shares"
                        + " (document_id, work_case_id, shared_with_user_id, purpose, status,"
                        + " expires_at, revoked_at)"
                        + " SELECT document_id, work_case_id, shared_with_user_id, purpose, status,"
                        + " expires_at, revoked_at FROM document_shares WHERE document_id = ?",
                documentId));
    }

    private void cleanUp(
            JdbcTemplate jdbcTemplate, Fixture fixture, Path storageBasePath) throws IOException {
        List<Long> workCaseIds = List.of(fixture.workCaseId, fixture.otherWorkCaseId);
        for (Long workCaseId : workCaseIds) {
            List<Long> documentIds = jdbcTemplate.queryForList(
                    "SELECT id FROM documents WHERE work_case_id = ?", Long.class, workCaseId);
            for (Long documentId : documentIds) {
                jdbcTemplate.update(
                        "DELETE FROM document_access_logs WHERE document_id = ?", documentId);
                jdbcTemplate.update(
                        "DELETE FROM document_shares WHERE document_id = ?", documentId);
                jdbcTemplate.update(
                        "DELETE FROM document_signatures WHERE document_id = ?", documentId);
                jdbcTemplate.update(
                        "DELETE FROM document_versions WHERE document_id = ?", documentId);
                jdbcTemplate.update("DELETE FROM documents WHERE id = ?", documentId);
            }
            jdbcTemplate.update(
                    "DELETE FROM wallet_transactions WHERE work_case_id = ?", workCaseId);
            jdbcTemplate.update("DELETE FROM settlements WHERE work_case_id = ?", workCaseId);
            jdbcTemplate.update("DELETE FROM escrows WHERE work_case_id = ?", workCaseId);
            jdbcTemplate.update("DELETE FROM work_contracts WHERE work_case_id = ?", workCaseId);
            jdbcTemplate.update("DELETE FROM work_invitations WHERE work_case_id = ?", workCaseId);
            jdbcTemplate.update("DELETE FROM work_cases WHERE id = ?", workCaseId);
            deleteStorageFixture(storageBasePath, workCaseId);
        }
        jdbcTemplate.update(
                "DELETE FROM idempotency_requests WHERE user_id IN (?, ?, ?)",
                fixture.ownerUserId,
                fixture.workerUserId,
                fixture.otherWorkerId);
        jdbcTemplate.update("DELETE FROM wallets WHERE user_id = ?", fixture.ownerUserId);
        jdbcTemplate.update("DELETE FROM workplaces WHERE id = ?", fixture.workplaceId);
        jdbcTemplate.update(
                "DELETE FROM users WHERE id IN (?, ?, ?)",
                fixture.ownerUserId,
                fixture.workerUserId,
                fixture.otherWorkerId);
    }

    private void deleteStorageFixture(Path storageBasePath, long workCaseId) throws IOException {
        Path workCasePath = storageBasePath.resolve("contracts").resolve(Long.toString(workCaseId));
        if (!Files.exists(workCasePath)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(workCasePath)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            });
        } catch (java.io.UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    /** 검증에 필요한 Fixture 식별자 묶음입니다. */
    private static final class Fixture {

        private final String suffix;
        private final long ownerUserId;
        private final long workerUserId;
        private final long otherWorkerId;
        private final long workplaceId;
        private final long workCaseId;
        private final long otherWorkCaseId;

        private String acceptedToken;
        private String otherToken;

        private Fixture(
                String suffix,
                long ownerUserId,
                long workerUserId,
                long otherWorkerId,
                long workplaceId,
                long workCaseId,
                long otherWorkCaseId) {
            this.suffix = suffix;
            this.ownerUserId = ownerUserId;
            this.workerUserId = workerUserId;
            this.otherWorkerId = otherWorkerId;
            this.workplaceId = workplaceId;
            this.workCaseId = workCaseId;
            this.otherWorkCaseId = otherWorkCaseId;
        }

        private String acceptKey() {
            return "accept-" + suffix;
        }

        private AuthPrincipal owner() {
            return new AuthPrincipal(ownerUserId, UserRole.OWNER, "수락검증사장");
        }

        private AuthPrincipal worker() {
            return new AuthPrincipal(workerUserId, UserRole.WORKER, "수락검증알바");
        }

        private AuthPrincipal otherWorker() {
            return new AuthPrincipal(otherWorkerId, UserRole.WORKER, "수락검증알바2");
        }
    }
}
