package com.gighub.invitation.service.impl;

import com.gighub.invitation.domain.InvitationStatus;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.common.exception.ValidationException;
import com.gighub.contract.ContractArtifactCommand;
import com.gighub.contract.ContractArtifactHandle;
import com.gighub.contract.ContractArtifactPort;
import com.gighub.idempotency.IdempotencyClaimResult;
import com.gighub.idempotency.IdempotencyClaimService;
import com.gighub.idempotency.IdempotencyKeys;
import com.gighub.invitation.config.InvitationProperties;
import com.gighub.invitation.dto.InvitationAcceptResponse;
import com.gighub.invitation.exception.InvitationNotFoundException;
import com.gighub.invitation.exception.InvitationTermsChangedException;
import com.gighub.invitation.mapper.InvitationMapperTestDouble;
import com.gighub.invitation.mapper.result.InvitationRow;
import com.gighub.invitation.service.InvitationAcceptResult;
import com.gighub.invitation.token.InvitationTokenCodec;
import com.gighub.member.domain.UserRole;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 수락 진입부의 Claim 생명주기와 Fingerprint 규칙을 확인합니다.
 */
class InvitationAcceptServiceImplTest {

    private static final long INVITATION_ID = 41L;
    private static final long WORK_CASE_ID = 7L;
    private static final long WORKER_ID = 11L;
    private static final long CLAIM_ID = 55L;
    private static final String KEY = "accept-key-0001";

    private final InvitationTokenCodec codec = new InvitationTokenCodec(
            InvitationProperties.of(
                    "accept-test-invitation-secret-0123456789", null, "http://localhost:5173")
    );
    private final String token = codec.deriveToken(INVITATION_ID);
    private final StubInvitationMapper mapper = new StubInvitationMapper();
    private final StubClaimService claimService = new StubClaimService();
    private final StubAggregateExecutor executor = new StubAggregateExecutor();
    private final StubArtifactPort artifactPort = new StubArtifactPort();

    @Test
    void firstSuccessRunsTheAggregateAndKeepsTheCompletedClaim() {
        mapper.invitation = pendingInvitation(3);

        InvitationAcceptResult result = service().accept(worker(), token, KEY);

        assertFalse(result.isReplayed());
        assertEquals(WORK_CASE_ID, result.getResponse().getWorkCaseId());
        assertEquals("HELD", result.getResponse().getEscrowStatus());
        assertEquals(1, executor.executions);
        // 성공 Claim은 본 처리 Transaction 안에서 이미 완료됐으므로 건드리지 않습니다.
        assertTrue(claimService.abandoned.isEmpty());
    }

    @Test
    void replayReturnsStoredResultWithoutRunningTheAggregate() {
        mapper.invitation = pendingInvitation(3);
        claimService.replayBody = "{\"data\":{\"workCaseId\":7,\"escrowStatus\":\"HELD\"}}";

        InvitationAcceptResult result = service().accept(worker(), token, KEY);

        assertTrue(result.isReplayed());
        assertEquals(WORK_CASE_ID, result.getResponse().getWorkCaseId());
        assertEquals("HELD", result.getResponse().getEscrowStatus());
        assertEquals(0, executor.executions, "Replay는 새 Aggregate를 만들지 않습니다.");
    }

    @Test
    void failedAggregateAbandonsTheClaimSoTheSameKeyCanRetry() {
        mapper.invitation = pendingInvitation(3);
        executor.failure = new InvitationTermsChangedException();

        assertThrows(
                InvitationTermsChangedException.class,
                () -> service().accept(worker(), token, KEY)
        );

        assertEquals(List.of(CLAIM_ID), claimService.abandoned);
    }

    @Test
    void contractFileIsPromotedOnlyAfterTheTransactionCommits() {
        mapper.invitation = pendingInvitation(3);

        service().accept(worker(), token, KEY);

        // 승격은 Commit 뒤 단계입니다. Transaction 안에서 부르면 Rollback된 계약의 파일이
        // 최종 위치에 남습니다.
        assertEquals(List.of(WORK_CASE_ID), artifactPort.promoted);
        assertTrue(artifactPort.discarded.isEmpty());
    }

    @Test
    void failedAggregateDiscardsPendingFilesBeforeAbandoningTheClaim() {
        mapper.invitation = pendingInvitation(3);
        executor.failure = new InvitationTermsChangedException();

        assertThrows(
                InvitationTermsChangedException.class,
                () -> service().accept(worker(), token, KEY)
        );

        // 파일 쓰기는 Rollback되지 않으므로 남은 임시 Object를 따로 지웁니다.
        assertEquals(List.of(WORK_CASE_ID), artifactPort.discarded);
        assertTrue(artifactPort.promoted.isEmpty());
    }

    @Test
    void fingerprintCombinesTokenHashAndExpectedTermsVersion() {
        mapper.invitation = pendingInvitation(3);

        service().accept(worker(), token, KEY);

        assertArrayEquals(expectedFingerprint(codec.hash(token), 3), claimService.fingerprint);
        assertEquals("INVITATION_ACCEPT", claimService.operationCode);
        assertEquals(WORKER_ID, claimService.userId);
        assertEquals(KEY, claimService.rawKey);
    }

    @Test
    void differentTermsVersionProducesADifferentFingerprint() {
        mapper.invitation = pendingInvitation(3);
        service().accept(worker(), token, KEY);
        byte[] first = claimService.fingerprint;

        mapper.invitation = pendingInvitation(4);
        service().accept(worker(), token, KEY);

        assertFalse(MessageDigest.isEqual(first, claimService.fingerprint));
    }

    @Test
    void nonWorkerIsRejectedBeforeAnyClaimIsCreated() {
        mapper.invitation = pendingInvitation(3);

        assertThrows(
                RoleMismatchException.class,
                () -> service().accept(owner(), token, KEY)
        );
        assertEquals(0, claimService.claims);
    }

    @Test
    void malformedAndUnknownTokensNeverCreateAClaim() {
        assertThrows(
                InvitationNotFoundException.class,
                () -> service().accept(worker(), "not-a-token", KEY)
        );

        mapper.invitation = null;
        assertThrows(
                InvitationNotFoundException.class,
                () -> service().accept(worker(), token, KEY)
        );

        // Claim을 만들면 같은 Key로 올바른 Token을 다시 시도할 수 없습니다.
        assertEquals(0, claimService.claims);
    }

    @Test
    void keyFormatIsValidatedByTheSharedRule() {
        mapper.invitation = pendingInvitation(3);
        claimService.validateKey = true;

        assertThrows(
                ValidationException.class,
                () -> service().accept(worker(), token, "has space")
        );
    }

    private InvitationAcceptServiceImpl service() {
        return new InvitationAcceptServiceImpl(
                mapper, codec, claimService, executor, new AcceptJson(), artifactPort);
    }

    private InvitationRow pendingInvitation(int expectedTermsVersion) {
        return InvitationRow.builder()
                .id(INVITATION_ID)
                .workCaseId(WORK_CASE_ID)
                .tokenHash(codec.hash(token))
                .status(InvitationStatus.PENDING)
                .expectedTermsVersion(expectedTermsVersion)
                .expiresAt(LocalDateTime.now().plusDays(1L))
                .build();
    }

    private static byte[] expectedFingerprint(byte[] tokenHash, int termsVersion) {
        String source = "INVITATION_ACCEPT\n"
                + HexFormat.of().formatHex(tokenHash) + "\n"
                + termsVersion;
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static AuthPrincipal worker() {
        return new AuthPrincipal(WORKER_ID, UserRole.WORKER, "김알바");
    }

    private static AuthPrincipal owner() {
        return new AuthPrincipal(3L, UserRole.OWNER, "김사장");
    }

    /** 조회 호출 자체를 관찰해야 해서 Mock 대신 Stub을 씁니다. */
    private static final class StubInvitationMapper extends InvitationMapperTestDouble {

        private InvitationRow invitation;

        @Override
        public InvitationRow findByTokenHash(byte[] tokenHash) {
            return invitation;
        }
    }

    /** Claim 호출 인자와 포기 여부를 관찰합니다. */
    private static final class StubClaimService implements IdempotencyClaimService {

        private final List<Long> abandoned = new ArrayList<>();

        private int claims;
        private long userId;
        private String operationCode;
        private String rawKey;
        private byte[] fingerprint;
        private String replayBody;
        private boolean validateKey;

        @Override
        public IdempotencyClaimResult claim(
                long userId, String operationCode, String rawKey, byte[] fingerprint) {
            if (validateKey) {
                IdempotencyKeys.validate(rawKey);
            }
            this.claims++;
            this.userId = userId;
            this.operationCode = operationCode;
            this.rawKey = rawKey;
            this.fingerprint = fingerprint;

            return replayBody == null
                    ? IdempotencyClaimResult.started(CLAIM_ID)
                    : IdempotencyClaimResult.replay(200, replayBody);
        }

        @Override
        public void complete(long claimId, int responseHttpStatus, String responseBody) {
            throw new UnsupportedOperationException("본 처리 Transaction 안에서만 부릅니다.");
        }

        @Override
        public void abandon(long claimId) {
            abandoned.add(claimId);
        }
    }

    /** 본 처리 성공·실패만 흉내 냅니다. */
    private static final class StubAggregateExecutor extends AcceptAggregateExecutor {

        private int executions;
        private RuntimeException failure;

        private StubAggregateExecutor() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public AcceptAggregateOutcome execute(
                AuthPrincipal principal,
                long invitationId,
                long workCaseId,
                byte[] tokenHash,
                long claimId) {
            executions++;
            if (failure != null) {
                throw failure;
            }
            return new AcceptAggregateOutcome(
                    InvitationAcceptResponse.held(workCaseId),
                    ContractArtifactHandle.of(workCaseId, 900L));
        }
    }

    /** 승격·정리 호출 순서를 관찰합니다. */
    private static final class StubArtifactPort implements ContractArtifactPort {

        private final List<Long> promoted = new ArrayList<>();
        private final List<Long> discarded = new ArrayList<>();

        @Override
        public ContractArtifactHandle prepare(ContractArtifactCommand command) {
            throw new UnsupportedOperationException("본 처리 Transaction 안에서만 부릅니다.");
        }

        @Override
        public void promote(ContractArtifactHandle handle) {
            promoted.add(handle.getWorkCaseId());
        }

        @Override
        public void discardPending(long workCaseId) {
            discarded.add(workCaseId);
        }
    }
}
