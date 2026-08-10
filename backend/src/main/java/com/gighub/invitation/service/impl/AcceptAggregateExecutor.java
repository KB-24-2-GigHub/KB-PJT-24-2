package com.gighub.invitation.service.impl;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.contract.ContractArtifactCommand;
import com.gighub.contract.ContractArtifactHandle;
import com.gighub.contract.ContractArtifactPort;
import com.gighub.contract.domain.AcceptedContract;
import com.gighub.idempotency.IdempotencyClaimService;
import com.gighub.invitation.dto.InvitationAcceptResponse;
import com.gighub.invitation.exception.InvitationExpiredException;
import com.gighub.invitation.service.AcceptanceWorkParticipant;
import com.gighub.invitation.service.result.AcceptanceWorkContext;
import com.gighub.settlement.service.SettlementReservationService;
import com.gighub.wallet.service.AcceptEscrowHold;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 수락의 outer Transaction과 participant 호출 순서를 소유합니다.
 *
 * <p>잠금·변경 순서는 Claim → Work → Invitation → Wallet → Contract/Document → Settlement →
 * Claim complete입니다. 각 participant는 같은 Transaction에 MANDATORY로 참여하고 자기 owner
 * Mapper만 사용합니다.</p>
 */
@Component
public class AcceptAggregateExecutor {

    private static final java.time.ZoneId DATABASE_ZONE = java.time.ZoneId.of("Asia/Seoul");

    private final AcceptanceWorkParticipant workParticipant;
    private final AcceptEscrowHold escrowHold;
    private final SettlementReservationService settlementReservationService;
    private final IdempotencyClaimService claimService;
    private final AcceptJson acceptJson;
    private final ContractArtifactPort contractArtifactPort;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public AcceptAggregateExecutor(
            AcceptanceWorkParticipant workParticipant,
            AcceptEscrowHold escrowHold,
            SettlementReservationService settlementReservationService,
            IdempotencyClaimService claimService,
            AcceptJson acceptJson,
            ContractArtifactPort contractArtifactPort) {
        this(
                workParticipant,
                escrowHold,
                settlementReservationService,
                claimService,
                acceptJson,
                contractArtifactPort,
                Clock.system(DATABASE_ZONE));
    }

    /** 만료·시작 시각 경계 테스트에서만 고정 Clock을 주입합니다. */
    AcceptAggregateExecutor(
            AcceptanceWorkParticipant workParticipant,
            AcceptEscrowHold escrowHold,
            SettlementReservationService settlementReservationService,
            IdempotencyClaimService claimService,
            AcceptJson acceptJson,
            ContractArtifactPort contractArtifactPort,
            Clock clock) {
        this.workParticipant = workParticipant;
        this.escrowHold = escrowHold;
        this.settlementReservationService = settlementReservationService;
        this.claimService = claimService;
        this.acceptJson = acceptJson;
        this.contractArtifactPort = contractArtifactPort;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = InvitationExpiredException.class)
    public AcceptAggregateOutcome execute(
            AuthPrincipal principal,
            long invitationId,
            long workCaseId,
            byte[] tokenHash,
            long claimId) {
        LocalDateTime acceptedAt = LocalDateTime.now(clock);
        AcceptanceWorkContext context = workParticipant.lockAndValidate(
                principal, invitationId, workCaseId, tokenHash, acceptedAt);

        workParticipant.confirm(context, principal.getUserId(), acceptedAt);
        escrowHold.hold(
                context.getEmployerId(),
                context.getWorkCaseId(),
                context.getDailyWage(),
                claimId,
                acceptedAt);

        AcceptedContract contract = workParticipant.createContract(
                context, principal.getUserId(), acceptedAt);
        ContractArtifactHandle artifact = contractArtifactPort.prepare(
                ContractArtifactCommand.from(contract));
        settlementReservationService.reserveWaiting(
                context.getWorkCaseId(), context.getDailyWage());

        InvitationAcceptResponse response = InvitationAcceptResponse.held(
                context.getWorkCaseId());
        claimService.complete(claimId, 200, acceptJson.writeResponseBody(response));
        return new AcceptAggregateOutcome(response, artifact);
    }
}
