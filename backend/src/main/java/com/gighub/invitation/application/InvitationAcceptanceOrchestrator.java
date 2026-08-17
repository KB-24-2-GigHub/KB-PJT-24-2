package com.gighub.invitation.application;

import com.gighub.contract.ContractArtifactCommand;
import com.gighub.contract.ContractArtifactHandle;
import com.gighub.contract.ContractArtifactPort;
import com.gighub.contract.domain.AcceptedContract;
import com.gighub.idempotency.IdempotencyClaimService;
import com.gighub.invitation.exception.InvitationExpiredException;
import com.gighub.invitation.service.AcceptanceWorkParticipant;
import com.gighub.invitation.service.result.AcceptanceWorkContext;
import com.gighub.notification.domain.NotificationType;
import com.gighub.notification.service.NotificationRecorder;
import com.gighub.notification.service.command.NotificationRecordCommand;
import com.gighub.settlement.service.SettlementReservationService;
import com.gighub.wallet.service.AcceptEscrowHold;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

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
    private final NotificationRecorder notificationRecorder;
    private final Clock clock;

    @Autowired
    public InvitationAcceptanceOrchestrator(
            AcceptanceWorkParticipant workParticipant,
            AcceptEscrowHold escrowHold,
            SettlementReservationService settlementReservationService,
            IdempotencyClaimService claimService,
            InvitationAcceptanceReplaySnapshotCodec replaySnapshotCodec,
            ContractArtifactPort contractArtifactPort,
            NotificationRecorder notificationRecorder) {
        this(
                workParticipant,
                escrowHold,
                settlementReservationService,
                claimService,
                replaySnapshotCodec,
                contractArtifactPort,
                notificationRecorder,
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
            NotificationRecorder notificationRecorder,
            Clock clock) {
        this.workParticipant = workParticipant;
        this.escrowHold = escrowHold;
        this.settlementReservationService = settlementReservationService;
        this.claimService = claimService;
        this.replaySnapshotCodec = replaySnapshotCodec;
        this.contractArtifactPort = contractArtifactPort;
        this.notificationRecorder = notificationRecorder;
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
        long escrowId = escrowHold.hold(
                context.getEmployerId(),
                context.getWorkCaseId(),
                context.getDailyWage(),
                command.getClaimId(),
                acceptedAt);
        recordAcceptanceNotifications(context, command.getWorkerId(), escrowId);

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

    /**
     * 근무 확정과 예치 완료를 양측에 알립니다.
     *
     * <p>이 Transaction 안에서 부르지만 실제 적재는 Commit 이후입니다. 알림이 실패해도 수락과
     * 예치는 유지되고, 이 Transaction이 Rollback되면 알림도 남지 않습니다(SPEC-384-01).</p>
     */
    private void recordAcceptanceNotifications(
            AcceptanceWorkContext context,
            long workerId,
            long escrowId) {
        List<Long> parties = List.of(context.getEmployerId(), workerId);
        notificationRecorder.record(NotificationRecordCommand.builder()
                .type(NotificationType.WORK_CASE_CONFIRMED)
                .sourceId(context.getWorkCaseId())
                .workCaseId(context.getWorkCaseId())
                .workCaseTitle(context.getTitle())
                .recipientUserIds(parties)
                .build());
        notificationRecorder.record(NotificationRecordCommand.builder()
                .type(NotificationType.ESCROW_HELD)
                .sourceId(escrowId)
                .workCaseId(context.getWorkCaseId())
                .workCaseTitle(context.getTitle())
                .recipientUserIds(parties)
                .build());
    }
}
