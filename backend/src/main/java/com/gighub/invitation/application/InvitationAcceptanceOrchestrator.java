package com.gighub.invitation.application;

import com.gighub.contract.ContractArtifactCommand;
import com.gighub.contract.ContractArtifactHandle;
import com.gighub.contract.ContractArtifactPort;
import com.gighub.contract.domain.AcceptedContract;
import com.gighub.idempotency.IdempotencyClaimService;
import com.gighub.invitation.exception.InvitationExpiredException;
import com.gighub.invitation.service.AcceptanceWorkParticipant;
import com.gighub.invitation.service.result.AcceptanceWorkContext;
import com.gighub.settlement.service.SettlementReservationService;
import com.gighub.wallet.service.AcceptEscrowHold;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 초대 수락에 참여하는 owner Service를 하나의 짧은 Application Transaction으로 조정합니다.
 *
 * <p>잠금 순서는 Work → Invitation → Wallet입니다. Work participant가 앞의 두 잠금과
 * expected-state 전이를 소유하고, Wallet·Document·Settlement·Idempotency participant는
 * 이 Transaction에 MANDATORY로 참여합니다. 교착/잠금 시간 초과가 발생하면 facade가 이
 * 명령 전체를 새 Transaction에서 다시 실행하며 participant 하나만 따로 재시도하지 않습니다.</p>
 */
@Service
public class InvitationAcceptanceOrchestrator {

    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Seoul");

    private final AcceptanceWorkParticipant workParticipant;
    private final AcceptEscrowHold escrowHold;
    private final SettlementReservationService settlementReservationService;
    private final IdempotencyClaimService claimService;
    private final InvitationAcceptanceReplaySnapshotCodec replaySnapshotCodec;
    private final ContractArtifactPort contractArtifactPort;
    private final Clock clock;

    @Autowired
    public InvitationAcceptanceOrchestrator(
            AcceptanceWorkParticipant workParticipant,
            AcceptEscrowHold escrowHold,
            SettlementReservationService settlementReservationService,
            IdempotencyClaimService claimService,
            InvitationAcceptanceReplaySnapshotCodec replaySnapshotCodec,
            ContractArtifactPort contractArtifactPort) {
        this(
                workParticipant,
                escrowHold,
                settlementReservationService,
                claimService,
                replaySnapshotCodec,
                contractArtifactPort,
                Clock.system(DATABASE_ZONE));
    }

    /** 만료·시작 시각 경계 테스트만 고정 Clock을 주입합니다. */
    public InvitationAcceptanceOrchestrator(
            AcceptanceWorkParticipant workParticipant,
            AcceptEscrowHold escrowHold,
            SettlementReservationService settlementReservationService,
            IdempotencyClaimService claimService,
            InvitationAcceptanceReplaySnapshotCodec replaySnapshotCodec,
            ContractArtifactPort contractArtifactPort,
            Clock clock) {
        this.workParticipant = workParticipant;
        this.escrowHold = escrowHold;
        this.settlementReservationService = settlementReservationService;
        this.claimService = claimService;
        this.replaySnapshotCodec = replaySnapshotCodec;
        this.contractArtifactPort = contractArtifactPort;
        this.clock = clock;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = InvitationExpiredException.class)
    public InvitationAcceptanceOutcome execute(InvitationAcceptanceCommand command) {
        LocalDateTime acceptedAt = LocalDateTime.now(clock);
        AcceptanceWorkContext context = workParticipant.lockAndValidate(
                command.getWorkerId(),
                command.getInvitationId(),
                command.getWorkCaseId(),
                command.getTokenHash(),
                acceptedAt);

        workParticipant.confirm(context, command.getWorkerId(), acceptedAt);
        escrowHold.hold(
                context.getEmployerId(),
                context.getWorkCaseId(),
                context.getDailyWage(),
                command.getClaimId(),
                acceptedAt);

        AcceptedContract contract = workParticipant.createContract(
                context, command.getWorkerId(), acceptedAt);
        ContractArtifactHandle artifact = contractArtifactPort.prepare(
                ContractArtifactCommand.from(contract));
        settlementReservationService.reserveWaiting(
                context.getWorkCaseId(), context.getDailyWage());

        InvitationAcceptanceResult result = InvitationAcceptanceResult.held(
                context.getWorkCaseId());
        claimService.complete(
                command.getClaimId(), 200, replaySnapshotCodec.writeResponseBody(result));
        return new InvitationAcceptanceOutcome(result, artifact);
    }
}
