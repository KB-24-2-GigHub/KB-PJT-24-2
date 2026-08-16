package com.gighub.invitation.service.impl;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.badge.service.BadgeApplicationService;
import com.gighub.config.RootConfig;
import com.gighub.contract.ContractArtifactPort;
import com.gighub.document.storage.DocumentStorageProperties;
import com.gighub.idempotency.IdempotencyClaimService;
import com.gighub.invitation.application.InvitationAcceptanceOrchestrator;
import com.gighub.invitation.application.InvitationAcceptanceReplaySnapshotCodec;
import com.gighub.invitation.config.InvitationLinkFactory;
import com.gighub.invitation.dto.InvitationDetailResponse;
import com.gighub.invitation.mapper.InvitationMapper;
import com.gighub.invitation.service.AcceptanceWorkParticipant;
import com.gighub.wallet.service.AcceptEscrowHold;
import com.gighub.invitation.service.InvitationAcceptResult;
import com.gighub.invitation.service.InvitationAcceptService;
import com.gighub.invitation.service.InvitationIssueResult;
import com.gighub.invitation.service.InvitationIssueService;
import com.gighub.invitation.service.InvitationQueryService;
import com.gighub.invitation.token.InvitationTokenCodec;
import com.gighub.member.domain.UserRole;
import com.gighub.settlement.service.SettlementReservationService;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.dto.WorkCaseDetailResponse;
import com.gighub.work.service.WorkCaseService;
import com.gighub.work.service.command.WorkCaseCreateCommand;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 장시간 이어지는 Work 흐름이 Thread, Transaction, Service 객체나 Session 메모리가 아니라
 * 커밋된 DB 상태만으로 재구성되는지 검증합니다.
 *
 * <p>각 단계는 서로 다른 ApplicationContext와 실제 Service Transaction을 사용합니다.
 * 사용자·사업장·초기 자금만 SQL Fixture로 준비하고, DRAFT·초대·수락 Aggregate는 반드시
 * Application Service를 통과합니다. 따라서 이 Test를 미구현 근태·지각·노쇼 기능의 구현
 * 증거로 사용하지 않습니다.</p>
 */
@Tag("database")
class LongLivedWorkLifecycleDatabaseIntegrationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long WAGE = 120_000L;
    private static final long INITIAL_AVAILABLE = 500_000L;

    @Test
    @Timeout(120)
    void committedDraftAndInvitationSurviveContextAndPrincipalReconstruction() throws Exception {
        MutableClock clock = new MutableClock(Instant.now(), SEOUL);
        Fixture fixture = new Fixture(
                UUID.randomUUID().toString().replace("-", "").substring(0, 10));

        try {
            createAndCommitDraft(clock, fixture);
            clock.advance(Duration.ofHours(2L));

            issueAndCommitInvitation(clock, fixture);
            clock.advance(Duration.ofHours(3L));

            queryAndCommitAcceptance(clock, fixture);
            clock.advance(Duration.ofHours(1L));

            reloadAndReplayFromCommittedState(clock, fixture);
        } finally {
            cleanUp(clock, fixture);
        }
    }

    /** 요청 A: 기반 행을 준비하고 실제 Work Service로 DRAFT를 커밋합니다. */
    private void createAndCommitDraft(MutableClock clock, Fixture fixture) {
        try (AnnotationConfigApplicationContext context = openContext(clock.snapshot())) {
            JdbcTemplate jdbc = jdbc(context);
            fixture.ownerUserId = insertUser(
                    jdbc, "qa285o" + fixture.suffix, "OWNER", "장기검증사장");
            fixture.workerUserId = insertUser(
                    jdbc, "qa285w" + fixture.suffix, "WORKER", "장기검증알바");
            fixture.workplaceId = insertWorkplace(jdbc, fixture.ownerUserId);
            jdbc.update(
                    "INSERT INTO wallets (user_id, available_balance, locked_balance)"
                            + " VALUES (?, ?, 0)",
                    fixture.ownerUserId,
                    INITIAL_AVAILABLE);
            fixture.storageBasePath = context
                    .getBean(DocumentStorageProperties.class)
                    .getBasePath();

            LocalDate workDate = LocalDate.ofInstant(clock.instant(), SEOUL).plusDays(30L);
            fixture.workCaseId = context.getBean(WorkCaseService.class).create(
                    fixture.owner(),
                    WorkCaseCreateCommand.builder()
                            .workplaceId(fixture.workplaceId)
                            .title("장기 생명주기 특성화 근무")
                            .workDate(workDate)
                            .startTime(LocalTime.of(9, 0))
                            .endTime(LocalTime.of(18, 0))
                            .breakMinutes(60)
                            .breakPaid(false)
                            .dailyWage(WAGE)
                            .build());

            assertEquals(
                    "DRAFT",
                    jdbc.queryForObject(
                            "SELECT status FROM work_cases WHERE id = ?",
                            String.class,
                            fixture.workCaseId));
        }
    }

    /** 요청 B: 이전 Context가 사라진 뒤 새 Context에서 초대를 발급하고 커밋합니다. */
    private void issueAndCommitInvitation(MutableClock clock, Fixture fixture) {
        try (AnnotationConfigApplicationContext context = openContext(clock.snapshot())) {
            InvitationIssueResult result = context.getBean(InvitationIssueService.class)
                    .issue(fixture.owner(), fixture.workCaseId);
            assertTrue(result.isCreated());
            fixture.token = tokenOf(result.getResponse().getInviteUrl());

            assertEquals(
                    "PENDING",
                    jdbc(context).queryForObject(
                            "SELECT status FROM work_invitations WHERE work_case_id = ?",
                            String.class,
                            fixture.workCaseId));
        }
    }

    /** 요청 C: 새 Principal로 조회한 뒤 별도 수락 Transaction을 커밋합니다. */
    private void queryAndCommitAcceptance(MutableClock clock, Fixture fixture) {
        try (AnnotationConfigApplicationContext context = openContext(clock.snapshot())) {
            AuthPrincipal reconstructedWorker = fixture.worker();
            InvitationDetailResponse invitation = context
                    .getBean(InvitationQueryService.class)
                    .findByToken(reconstructedWorker, fixture.token);
            assertEquals("장기 생명주기 특성화 근무", invitation.getTitle());
            assertEquals(WAGE, invitation.getDailyWage());

            InvitationAcceptResult accepted = context
                    .getBean(InvitationAcceptService.class)
                    .accept(reconstructedWorker, fixture.token, fixture.acceptKey());
            assertFalse(accepted.isReplayed());
            assertEquals(fixture.workCaseId, accepted.getResult().getWorkCaseId());
            assertEquals("HELD", accepted.getResult().getEscrowStatus());
        }
    }

    /** 요청 D: 수락 Context도 폐기한 뒤 DB 재조회와 저장 응답 Replay를 검증합니다. */
    private void reloadAndReplayFromCommittedState(MutableClock clock, Fixture fixture) {
        try (AnnotationConfigApplicationContext context = openContext(clock.snapshot())) {
            AuthPrincipal reconstructedWorker = fixture.worker();
            InvitationAcceptResult replay = context
                    .getBean(InvitationAcceptService.class)
                    .accept(reconstructedWorker, fixture.token, fixture.acceptKey());
            assertTrue(replay.isReplayed());
            assertEquals(fixture.workCaseId, replay.getResult().getWorkCaseId());

            WorkCaseDetailResponse detail = context.getBean(WorkCaseService.class)
                    .detail(reconstructedWorker, fixture.workCaseId);
            assertEquals(WorkCaseStatus.ACCEPTED, detail.getStatus());
            assertEquals(fixture.workerUserId, detail.getWorker().getWorkerId());
            assertNotNull(detail.getContract());
            assertNotNull(detail.getContract().getDocumentId());
            assertEquals("ACCEPTED", detail.getLatestInvitation().getStatus());
            assertEquals("HELD", detail.getEscrow().getStatus());
            assertEquals(WAGE, detail.getEscrow().getAmount());
            assertEquals("WAITING", detail.getSettlement().getStatus());

            JdbcTemplate jdbc = jdbc(context);
            assertEquals(1, countByWorkCase(jdbc, "work_contracts", fixture.workCaseId));
            assertEquals(1, countByWorkCase(jdbc, "documents", fixture.workCaseId));
            assertEquals(1, countByWorkCase(jdbc, "escrows", fixture.workCaseId));
            assertEquals(1, countByWorkCase(jdbc, "settlements", fixture.workCaseId));
            assertEquals(1, countByWorkCase(jdbc, "wallet_transactions", fixture.workCaseId));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM idempotency_requests"
                            + " WHERE user_id = ? AND status = 'COMPLETED'",
                    Integer.class,
                    fixture.workerUserId));
            assertEquals(
                    INITIAL_AVAILABLE - WAGE,
                    balance(jdbc, fixture.ownerUserId, "available_balance"));
            assertEquals(WAGE, balance(jdbc, fixture.ownerUserId, "locked_balance"));
        }
    }

    /**
     * 고정 Clock 생성자를 쓰는 Bean을 Primary로 등록하되, Spring이 Transaction Proxy를
     * 그대로 적용하도록 BeanDefinition 상태에서 등록합니다.
     */
    private AnnotationConfigApplicationContext openContext(Clock clock) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(RootConfig.class);
        context.registerBean(
                "characterizationInvitationIssueService",
                InvitationIssueServiceImpl.class,
                () -> new InvitationIssueServiceImpl(
                        context.getBean(InvitationMapper.class),
                        context.getBean(InvitationTokenCodec.class),
                        context.getBean(InvitationLinkFactory.class),
                        context.getBean(BadgeApplicationService.class),
                        clock),
                definition -> definition.setPrimary(true));
        context.registerBean(
                "characterizationInvitationQueryService",
                InvitationQueryServiceImpl.class,
                () -> new InvitationQueryServiceImpl(
                        context.getBean(InvitationMapper.class),
                        context.getBean(InvitationTokenCodec.class),
                        context.getBean(BadgeApplicationService.class),
                        clock),
                definition -> definition.setPrimary(true));
        context.registerBean(
                "characterizationInvitationAcceptanceOrchestrator",
                InvitationAcceptanceOrchestrator.class,
                () -> new InvitationAcceptanceOrchestrator(
                        context.getBean(AcceptanceWorkParticipant.class),
                        context.getBean(AcceptEscrowHold.class),
                        context.getBean(SettlementReservationService.class),
                        context.getBean(IdempotencyClaimService.class),
                        context.getBean(InvitationAcceptanceReplaySnapshotCodec.class),
                        context.getBean(ContractArtifactPort.class),
                        clock),
                definition -> definition.setPrimary(true));
        context.refresh();
        return context;
    }

    private JdbcTemplate jdbc(AnnotationConfigApplicationContext context) {
        return new JdbcTemplate(context.getBean(DataSource.class));
    }

    private long insertUser(
            JdbcTemplate jdbc,
            String loginId,
            String role,
            String name) {
        jdbc.update(
                "INSERT INTO users (login_id, email, password_hash, name, role)"
                        + " VALUES (?, ?, ?, ?, ?)",
                loginId,
                loginId + "@example.com",
                "$2a$10$abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQR",
                name,
                role);
        return jdbc.queryForObject(
                "SELECT id FROM users WHERE login_id = ?", Long.class, loginId);
    }

    private long insertWorkplace(JdbcTemplate jdbc, long ownerUserId) {
        String businessNumber = String.format(
                "%010d", ThreadLocalRandom.current().nextLong(1_000_000_0000L));
        jdbc.update(
                "INSERT INTO workplaces (owner_user_id, business_registration_number, name,"
                        + " representative_name, road_address, detail_address, phone, latitude,"
                        + " longitude, radius_meters, status)"
                        + " VALUES (?, ?, '장기검증점', '김사장', '서울 강남구 테헤란로 1',"
                        + " '2층', '0212345678', 37.4980000, 127.0270000, 100.00, 'ACTIVE')",
                ownerUserId,
                businessNumber);
        return jdbc.queryForObject(
                "SELECT id FROM workplaces WHERE business_registration_number = ?",
                Long.class,
                businessNumber);
    }

    private int countByWorkCase(JdbcTemplate jdbc, String table, long workCaseId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE work_case_id = ?",
                Integer.class,
                workCaseId);
    }

    private long balance(JdbcTemplate jdbc, long userId, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM wallets WHERE user_id = ?",
                Long.class,
                userId);
    }

    private static String tokenOf(String inviteUrl) {
        return inviteUrl.substring(inviteUrl.lastIndexOf('/') + 1);
    }

    private void cleanUp(MutableClock clock, Fixture fixture) throws IOException {
        if (fixture.ownerUserId == null) {
            return;
        }
        RuntimeException databaseFailure = null;
        try (AnnotationConfigApplicationContext context = openContext(clock.snapshot())) {
            JdbcTemplate jdbc = jdbc(context);
            if (fixture.workCaseId != null) {
                jdbc.update(
                        "DELETE FROM document_access_logs WHERE document_id IN"
                                + " (SELECT id FROM documents WHERE work_case_id = ?)",
                        fixture.workCaseId);
                jdbc.update(
                        "DELETE FROM document_shares WHERE document_id IN"
                                + " (SELECT id FROM documents WHERE work_case_id = ?)",
                        fixture.workCaseId);
                jdbc.update(
                        "DELETE FROM document_signatures WHERE document_id IN"
                                + " (SELECT id FROM documents WHERE work_case_id = ?)",
                        fixture.workCaseId);
                jdbc.update(
                        "DELETE FROM document_versions WHERE document_id IN"
                                + " (SELECT id FROM documents WHERE work_case_id = ?)",
                        fixture.workCaseId);
                jdbc.update("DELETE FROM documents WHERE work_case_id = ?", fixture.workCaseId);
                jdbc.update(
                        "DELETE FROM wallet_transactions WHERE work_case_id = ?",
                        fixture.workCaseId);
                jdbc.update("DELETE FROM settlements WHERE work_case_id = ?", fixture.workCaseId);
                jdbc.update("DELETE FROM escrows WHERE work_case_id = ?", fixture.workCaseId);
                jdbc.update(
                        "DELETE FROM work_contracts WHERE work_case_id = ?",
                        fixture.workCaseId);
                jdbc.update(
                        "DELETE FROM work_invitations WHERE work_case_id = ?",
                        fixture.workCaseId);
                jdbc.update("DELETE FROM work_cases WHERE id = ?", fixture.workCaseId);
            }
            if (fixture.workerUserId != null) {
                jdbc.update(
                        "DELETE FROM idempotency_requests WHERE user_id = ?",
                        fixture.workerUserId);
            }
            // 초대 조회가 실제로 OWNER 배지를 재계산·Upsert하므로 users보다 먼저 지운다.
            jdbc.update("DELETE FROM user_badges WHERE user_id = ?", fixture.ownerUserId);
            if (fixture.workerUserId != null) {
                jdbc.update("DELETE FROM user_badges WHERE user_id = ?", fixture.workerUserId);
            }
            jdbc.update("DELETE FROM wallets WHERE user_id = ?", fixture.ownerUserId);
            if (fixture.workplaceId != null) {
                jdbc.update("DELETE FROM workplaces WHERE id = ?", fixture.workplaceId);
            }
            if (fixture.workerUserId == null) {
                jdbc.update("DELETE FROM users WHERE id = ?", fixture.ownerUserId);
            } else {
                jdbc.update(
                        "DELETE FROM users WHERE id IN (?, ?)",
                        fixture.ownerUserId,
                        fixture.workerUserId);
            }
        } catch (RuntimeException failure) {
            databaseFailure = failure;
            throw failure;
        } finally {
            try {
                deleteStorageFixture(fixture.storageBasePath, fixture.workCaseId);
            } catch (IOException storageFailure) {
                if (databaseFailure != null) {
                    databaseFailure.addSuppressed(storageFailure);
                } else {
                    throw storageFailure;
                }
            }
        }
    }

    private void deleteStorageFixture(Path storageBasePath, Long workCaseId) throws IOException {
        if (storageBasePath == null || workCaseId == null) {
            return;
        }
        Path workCasePath = storageBasePath.resolve("contracts").resolve(workCaseId.toString());
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

    /** 테스트 단계 사이에서 같은 업무 시각을 명시적으로 전진시킵니다. */
    private static final class MutableClock extends Clock {

        private Instant current;
        private final ZoneId zone;

        private MutableClock(Instant current, ZoneId zone) {
            this.current = current;
            this.zone = zone;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        private Clock snapshot() {
            return Clock.fixed(current, zone);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return new MutableClock(current, requestedZone);
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    /** SQL Fixture ID와 요청 사이에 전달되는 Token만 보존합니다. */
    private static final class Fixture {

        private final String suffix;
        private Long ownerUserId;
        private Long workerUserId;
        private Long workplaceId;
        private Long workCaseId;
        private String token;
        private Path storageBasePath;

        private Fixture(String suffix) {
            this.suffix = suffix;
        }

        private AuthPrincipal owner() {
            return new AuthPrincipal(ownerUserId, UserRole.OWNER, "장기검증사장");
        }

        private AuthPrincipal worker() {
            return new AuthPrincipal(workerUserId, UserRole.WORKER, "장기검증알바");
        }

        private String acceptKey() {
            return "long-lived-accept-" + suffix;
        }
    }
}
