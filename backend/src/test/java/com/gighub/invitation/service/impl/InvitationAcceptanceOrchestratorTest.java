package com.gighub.invitation.service.impl;

import com.gighub.invitation.domain.InvitationStatus;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.common.exception.ForbiddenException;
import com.gighub.common.exception.WorkCaseLockedException;
import com.gighub.contract.domain.AcceptedContract;
import com.gighub.contract.ContractArtifactCommand;
import com.gighub.contract.ContractArtifactHandle;
import com.gighub.contract.ContractArtifactPort;
import com.gighub.contract.mapper.WorkContractMapper;
import com.gighub.idempotency.IdempotencyClaimService;
import com.gighub.invitation.application.InvitationAcceptanceCommand;
import com.gighub.invitation.application.InvitationAcceptanceOrchestrator;
import com.gighub.invitation.application.InvitationAcceptanceOutcome;
import com.gighub.invitation.config.InvitationProperties;
import com.gighub.invitation.exception.InvitationAlreadyAcceptedException;
import com.gighub.invitation.exception.InvitationExpiredException;
import com.gighub.invitation.exception.InvitationNotFoundException;
import com.gighub.invitation.exception.InvitationTermsChangedException;
import com.gighub.invitation.mapper.InvitationMapperTestDouble;
import com.gighub.invitation.mapper.result.AcceptWorkCaseLockRow;
import com.gighub.invitation.mapper.result.InvitationRow;
import com.gighub.invitation.service.AcceptanceWorkParticipant;
import com.gighub.invitation.service.result.AcceptanceWorkContext;
import com.gighub.invitation.token.InvitationTokenCodec;
import com.gighub.settlement.service.SettlementReservationService;
import com.gighub.wallet.service.AcceptEscrowHold;
import com.gighub.work.mapper.WorkCaseMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 잠금 뒤 재검증 순서와, 실패 시 아무 것도 쓰지 않는지 확인합니다.
 *
 * <p>DB 통합 테스트가 도달할 수 없는 방어선도 여기서 확인합니다. 역할이 WORKER인데 같은
 * 근무의 OWNER이기도 한 어긋난 데이터가 그런 경우입니다.</p>
 */
class InvitationAcceptanceOrchestratorTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long INVITATION_ID = 41L;
    private static final long WORK_CASE_ID = 7L;
    private static final long OWNER_ID = 3L;
    private static final long WORKER_ID = 11L;
    private static final long CLAIM_ID = 55L;
    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 8, 20, 10, 0);

    private final InvitationTokenCodec codec = new InvitationTokenCodec(
            InvitationProperties.of(
                    "executor-test-invitation-secret-0123456789", null, "http://localhost:5173")
    );
    private final byte[] tokenHash = codec.hash(codec.deriveToken(INVITATION_ID));
    private final StubInvitationMapper mapper = new StubInvitationMapper();
    private final StubEscrowHold escrowHold = new StubEscrowHold();

    @Test
    void ownsOneNewOuterTransactionAndPreservesExpiryCommit() throws Exception {
        Transactional transactional = InvitationAcceptanceOrchestrator.class
                .getMethod("execute", InvitationAcceptanceCommand.class)
                .getAnnotation(Transactional.class);

        assertNotNull(transactional);
        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation());
        assertTrue(Arrays.asList(transactional.noRollbackFor())
                .contains(InvitationExpiredException.class));
    }

    @Test
    void callsOwnerParticipantsInTheFixedAcceptanceOrder() {
        AcceptanceWorkParticipant workParticipant = mock(AcceptanceWorkParticipant.class);
        AcceptEscrowHold walletParticipant = mock(AcceptEscrowHold.class);
        SettlementReservationService settlementParticipant =
                mock(SettlementReservationService.class);
        IdempotencyClaimService claimParticipant = mock(IdempotencyClaimService.class);
        ContractArtifactPort documentParticipant = mock(ContractArtifactPort.class);
        AcceptanceWorkContext context = AcceptanceWorkContext.builder()
                .invitationId(INVITATION_ID)
                .workCaseId(WORK_CASE_ID)
                .employerId(OWNER_ID)
                .dailyWage(120_000L)
                .build();
        AcceptedContract contract = mock(AcceptedContract.class);
        ContractArtifactHandle artifact = ContractArtifactHandle.of(WORK_CASE_ID, 900L);
        when(workParticipant.lockAndValidate(
                anyLong(), anyLong(), anyLong(), any(byte[].class), any()))
                .thenReturn(context);
        when(workParticipant.createContract(any(), anyLong(), any())).thenReturn(contract);
        when(contract.getWorkCaseId()).thenReturn(WORK_CASE_ID);
        when(contract.getContractId()).thenReturn(900L);
        when(contract.getAcceptedAt()).thenReturn(STARTS_AT.minusDays(1L));
        when(documentParticipant.prepare(any())).thenReturn(artifact);

        InvitationAcceptanceOutcome outcome = new InvitationAcceptanceOrchestrator(
                workParticipant,
                walletParticipant,
                settlementParticipant,
                claimParticipant,
                new AcceptJson(),
                documentParticipant,
                Clock.fixed(STARTS_AT.minusDays(1L).atZone(SEOUL).toInstant(), SEOUL))
                .execute(InvitationAcceptanceCommand.of(
                        WORKER_ID, INVITATION_ID, WORK_CASE_ID, tokenHash, CLAIM_ID));

        org.mockito.InOrder order = inOrder(
                workParticipant,
                walletParticipant,
                documentParticipant,
                settlementParticipant,
                claimParticipant);
        order.verify(workParticipant).lockAndValidate(
                anyLong(), anyLong(), anyLong(), any(byte[].class), any());
        order.verify(workParticipant).confirm(any(), anyLong(), any());
        order.verify(walletParticipant).hold(
                anyLong(), anyLong(), anyLong(), anyLong(), any());
        order.verify(workParticipant).createContract(any(), anyLong(), any());
        order.verify(documentParticipant).prepare(any());
        order.verify(settlementParticipant).reserveWaiting(WORK_CASE_ID, 120_000L);
        order.verify(claimParticipant).complete(anyLong(), eq(200), any());
        assertEquals(WORK_CASE_ID, outcome.getResult().getWorkCaseId());
        assertEquals(artifact, outcome.getArtifact());
    }

    @Test
    void sameUserOnBothSidesIsRejectedAsAPartyProblem() {
        mapper.workCase = draftWorkCase(1).toBuilder().employerId(WORKER_ID).build();
        mapper.invitation = pendingInvitation(1);

        // 역할은 WORKER지만 그 근무의 OWNER이기도 한 경우입니다. 계약 상대가 자기 자신이 됩니다.
        assertThrows(
                ForbiddenException.class,
                () -> execute(STARTS_AT.minusDays(1L))
        );
        assertTrue(escrowHold.holds.isEmpty());
    }

    @Test
    void lockedInvitationMustStillMatchTheRequestToken() {
        mapper.workCase = draftWorkCase(1);
        mapper.invitation = pendingInvitation(1).toBuilder()
                .tokenHash(codec.hash("another-token-value"))
                .build();

        assertThrows(InvitationNotFoundException.class, () -> execute(STARTS_AT.minusDays(1L)));
    }

    @Test
    void terminalInvitationStatesStopBeforeAnyWrite() {
        mapper.workCase = draftWorkCase(1);

        mapper.invitation = pendingInvitation(1).toBuilder()
                .status(InvitationStatus.ACCEPTED).build();
        assertThrows(
                InvitationAlreadyAcceptedException.class,
                () -> execute(STARTS_AT.minusDays(1L)));

        mapper.invitation = pendingInvitation(1).toBuilder()
                .status(InvitationStatus.EXPIRED).build();
        assertThrows(InvitationExpiredException.class, () -> execute(STARTS_AT.minusDays(1L)));

        assertTrue(mapper.assigned.isEmpty(), "검증 실패 뒤에는 매칭을 남기지 않습니다.");
        assertTrue(escrowHold.holds.isEmpty());
    }

    @Test
    void overdueInvitationIsExpiredAndReported() {
        mapper.workCase = draftWorkCase(1);
        mapper.invitation = pendingInvitation(1);

        assertThrows(InvitationExpiredException.class, () -> execute(STARTS_AT));

        // 이 전이는 410과 함께 보존돼야 활성 초대 Slot이 풀립니다.
        assertEquals(List.of(INVITATION_ID), mapper.expired);
    }

    @Test
    void overdueInvitationStopsWhenTheLockedPendingRowWasNotUpdated() {
        mapper.workCase = draftWorkCase(1);
        mapper.invitation = pendingInvitation(1);
        mapper.markExpiredResult = 0;

        assertThrows(IllegalStateException.class, () -> execute(STARTS_AT));
        assertTrue(mapper.assigned.isEmpty());
    }

    @Test
    void changedTermsStopBeforeAnyWrite() {
        mapper.workCase = draftWorkCase(2);
        mapper.invitation = pendingInvitation(1);

        assertThrows(
                InvitationTermsChangedException.class,
                () -> execute(STARTS_AT.minusDays(1L)));
        assertTrue(mapper.assigned.isEmpty());
    }

    /**
     * 이미 다른 WORKER가 매칭된 근무는 확정할 수 없습니다.
     *
     * <p>"시작 시각이 지난 근무"는 여기서 확인하지 않습니다. 초대의 만료 시각이 곧 근무 시작
     * 시각이라 만료 검사가 항상 먼저 걸리기 때문입니다. 근무 시각 검사는 조건이 어긋난
     * 데이터를 대비한 방어선으로 남습니다.</p>
     */
    @Test
    void matchedWorkCaseIsRejected() {
        mapper.invitation = pendingInvitation(1);
        mapper.workCase = draftWorkCase(1).toBuilder().workerId(99L).build();

        assertThrows(WorkCaseLockedException.class, () -> execute(STARTS_AT.minusDays(1L)));
        assertTrue(escrowHold.holds.isEmpty());
    }

    @Test
    void lockOrderIsWorkCaseThenInvitation() {
        mapper.workCase = draftWorkCase(1);
        mapper.invitation = pendingInvitation(1).toBuilder()
                .status(InvitationStatus.REVOKED).build();

        assertThrows(RuntimeException.class, () -> execute(STARTS_AT.minusDays(1L)));

        // 조건 수정(#154)과 발급도 근무를 먼저 잠급니다. 순서가 어긋나면 교착이 납니다.
        assertEquals(List.of("lockWorkCase", "lockInvitation"), mapper.calls);
    }

    private void execute(LocalDateTime now) {
        WorkCaseMapper workCaseMapper = mock(WorkCaseMapper.class);
        AcceptanceWorkParticipant workParticipant = new AcceptanceWorkParticipantImpl(
                mapper,
                workCaseMapper,
                mock(WorkContractMapper.class),
                mock(com.gighub.member.service.MemberIdentityQueryService.class),
                new AcceptJson());
        new InvitationAcceptanceOrchestrator(
                workParticipant,
                escrowHold,
                mock(SettlementReservationService.class),
                mock(IdempotencyClaimService.class),
                new AcceptJson(),
                new StubArtifactPort(),
                Clock.fixed(now.atZone(SEOUL).toInstant(), SEOUL))
                .execute(InvitationAcceptanceCommand.of(
                        WORKER_ID, INVITATION_ID, WORK_CASE_ID, tokenHash, CLAIM_ID));
    }

    private static AcceptWorkCaseLockRow draftWorkCase(int termsVersion) {
        return AcceptWorkCaseLockRow.builder()
                .workCaseId(WORK_CASE_ID)
                .employerId(OWNER_ID)
                .workerId(null)
                .status(WorkCaseStatus.DRAFT)
                .termsVersion(termsVersion)
                .title("주말 홀 서빙")
                .startsAt(STARTS_AT)
                .endsAt(STARTS_AT.plusHours(8L))
                .breakMinutes(60)
                .breakPaid(false)
                .dailyWage(120_000L)
                .workplaceName("강남점")
                .workplaceAddress("서울 강남구 테헤란로 1")
                .allowedRadiusMeters(new BigDecimal("100.00"))
                .build();
    }

    private InvitationRow pendingInvitation(int expectedTermsVersion) {
        return InvitationRow.builder()
                .id(INVITATION_ID)
                .workCaseId(WORK_CASE_ID)
                .tokenHash(tokenHash)
                .status(InvitationStatus.PENDING)
                .expectedTermsVersion(expectedTermsVersion)
                .expiresAt(STARTS_AT)
                .build();
    }

    /** 호출 순서와 쓰기 여부를 관찰합니다. */
    private static final class StubInvitationMapper extends InvitationMapperTestDouble {

        private final List<String> calls = new ArrayList<>();
        private final List<Long> assigned = new ArrayList<>();
        private final List<Long> expired = new ArrayList<>();

        private AcceptWorkCaseLockRow workCase;
        private InvitationRow invitation;
        private int markExpiredResult = 1;

        @Override
        public AcceptWorkCaseLockRow lockWorkCaseForAccept(long workCaseId) {
            calls.add("lockWorkCase");
            return workCase;
        }

        @Override
        public InvitationRow lockInvitationById(long invitationId) {
            calls.add("lockInvitation");
            return invitation;
        }

        @Override
        public int markExpired(long invitationId) {
            if (markExpiredResult == 1) {
                expired.add(invitationId);
            }
            return markExpiredResult;
        }

    }

    /** 예치가 호출됐는지만 관찰합니다. */
    private static final class StubEscrowHold implements AcceptEscrowHold {

        private final List<Long> holds = new ArrayList<>();

        @Override
        public long hold(
                long employerId,
                long workCaseId,
                long amount,
                long claimId,
                LocalDateTime acceptedAt) {
            holds.add(workCaseId);
            return 900L;
        }
    }

    /** 파일 준비는 이 테스트의 관심사가 아닙니다. */
    private static final class StubArtifactPort implements ContractArtifactPort {

        @Override
        public ContractArtifactHandle prepare(ContractArtifactCommand command) {
            return ContractArtifactHandle.nothing();
        }

        @Override
        public void promote(ContractArtifactHandle handle) {
        }

        @Override
        public void discardPending(long workCaseId) {
        }
    }
}
