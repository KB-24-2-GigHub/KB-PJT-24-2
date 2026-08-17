package com.gighub.settlement.service.impl;

import com.gighub.settlement.dto.SettlementSnapshot;
import com.gighub.settlement.domain.SettlementPayoutTrigger;
import com.gighub.notification.domain.NotificationType;
import com.gighub.notification.service.NotificationRecorder;
import com.gighub.notification.service.command.NotificationRecordCommand;
import com.gighub.settlement.mapper.SettlementMapper;
import com.gighub.settlement.service.SettlementPayoutExecutor;
import com.gighub.settlement.service.SettlementPayoutResultValidator.CompletedSettlementFacts;
import com.gighub.settlement.service.command.SettlementPayoutCommand;
import com.gighub.settlement.service.policy.SettlementPayoutDecision;
import com.gighub.settlement.service.policy.SettlementPayoutPolicy;
import com.gighub.settlement.service.policy.SettlementPayoutPolicy.SettlementFacts;
import com.gighub.settlement.service.policy.SettlementPayoutRejectedException;
import com.gighub.settlement.service.result.SettlementResult;
import com.gighub.wallet.exception.EscrowIntegrityException;
import com.gighub.wallet.idempotency.WalletIdempotencyKeys;
import com.gighub.wallet.service.SettlementWalletService;
import com.gighub.wallet.service.SettlementWalletService.SettlementAmounts;
import com.gighub.wallet.service.SettlementWalletService.SettlementWalletLock;
import com.gighub.wallet.service.command.SettlementWalletCommand;
import com.gighub.wallet.service.result.SettlementEscrowSnapshot;
import com.gighub.work.contract.WorkCaseEscrowSnapshot;
import com.gighub.work.service.WorkSettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.gighub.settlement.service.SettlementPayoutResultValidator.validateAndBuild;

/** 정상 정산의 상태·에스크로·지갑·원장을 호출자의 Transaction 안에서 원자 처리합니다. */
@Service
@RequiredArgsConstructor
public class SettlementPayoutExecutorImpl implements SettlementPayoutExecutor {

    private final SettlementMapper settlementMapper;
    private final WorkSettlementService workSettlementService;
    private final SettlementWalletService settlementWalletService;
    private final NotificationRecorder notificationRecorder;
    private final SettlementPayoutPolicy payoutPolicy = new SettlementPayoutPolicy();

    /**
     * Work → Settlement → Dispute → Escrow → Wallet 순서를 모든 호출자가 공유합니다.
     *
     * <p>수동 승인과 여러 Scheduler 인스턴스가 같은 두 지갑을 반대 역할로 다루더라도,
     * Wallet 내부에서 ID 오름차순으로 잠그기 전 상위 Aggregate 잠금 순서가 뒤집히지 않게
     * 고정하여 교착 순환을 막습니다.</p>
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SettlementResult execute(SettlementPayoutCommand command) {
        WorkCaseEscrowSnapshot work =
                workSettlementService.lockEscrowContext(command.getWorkCaseId());
        requireAllowed(payoutPolicy.assessWork(command, work));

        SettlementSnapshot settlement =
                settlementMapper.findByWorkCaseIdForUpdate(command.getWorkCaseId());
        SettlementFacts settlementFacts = facts(settlement);
        requireAllowed(payoutPolicy.assessSettlement(command, work, settlementFacts));

        SettlementWalletCommand walletCommand = walletCommand(work, settlement);
        boolean hasBlockingDispute = !settlementMapper.findBlockingDisputeIdsForUpdate(
                work.getWorkCaseId()).isEmpty();
        SettlementEscrowSnapshot escrow = settlementWalletService.lockEscrow(walletCommand);
        requireAllowed(payoutPolicy.assessPayout(
                command, work, settlementFacts, escrow, hasBlockingDispute));
        SettlementWalletLock walletLock =
                settlementWalletService.lockPayoutWallets(
                        walletCommand, escrow.getEscrowId());
        settlementWalletService.verifyHeldEscrow(
                walletCommand, escrow.getEscrowId(), walletLock);

        Long approvedByUserId = command.getActorUserId();
        int processingRows = command.getTrigger() == SettlementPayoutTrigger.SCHEDULER
                ? settlementMapper.transitionEligibleScheduledToProcessing(
                        settlement.getSettlementId(), command.getEligibilityTime())
                : settlementMapper.transitionScheduledToProcessing(
                        settlement.getSettlementId(), approvedByUserId);
        if (processingRows != 1) {
            throw new EscrowIntegrityException("정산을 처리 중 상태로 전환하지 못했습니다.");
        }

        // Escrow와 지갑 두 개, 원장 두 개는 이 Transaction 밖에서 따로 Commit되지 않습니다.
        SettlementAmounts amounts = settlementWalletService.release(
                walletCommand, escrow.getEscrowId(), walletLock);
        if (settlementMapper.transitionProcessingToCompleted(
                settlement.getSettlementId(), approvedByUserId) != 1) {
            throw new EscrowIntegrityException("정산을 완료 상태로 전환하지 못했습니다.");
        }

        SettlementSnapshot completed =
                settlementMapper.findByWorkCaseIdForUpdate(work.getWorkCaseId());
        settlementWalletService.verifyCompletedPayout(walletCommand, walletLock);
        // 적재는 이 Transaction 이 Commit 된 뒤다. 알림 실패가 지급을 되돌리지 않는다.
        notificationRecorder.record(NotificationRecordCommand.builder()
                .type(NotificationType.SETTLED)
                .sourceId(completed.getSettlementId())
                .workCaseId(work.getWorkCaseId())
                .workCaseTitle(work.getTitle())
                .recipientUserIds(List.of(work.getEmployerId(), work.getWorkerId()))
                .build());
        return validateAndBuild(
                completedFacts(completed), work, approvedByUserId, amounts);
    }

    private SettlementWalletCommand walletCommand(
            WorkCaseEscrowSnapshot work, SettlementSnapshot settlement) {
        return SettlementWalletCommand.builder()
                .workCaseId(work.getWorkCaseId())
                .employerId(work.getEmployerId())
                .workerId(work.getWorkerId())
                .amount(work.getAgreedWage())
                .employerLedgerKey(WalletIdempotencyKeys.settlementReleaseOwner(
                        settlement.getSettlementId()))
                .workerLedgerKey(WalletIdempotencyKeys.settlementReleaseWorker(
                        settlement.getSettlementId()))
                .build();
    }

    private SettlementFacts facts(SettlementSnapshot settlement) {
        if (settlement == null) {
            return null;
        }
        return new SettlementFacts(
                settlement.getSettlementId(),
                settlement.getWorkCaseId(),
                settlement.getAmount(),
                settlement.getStatus(),
                settlement.getDueAt(),
                settlement.getNextRetryAt());
    }

    private CompletedSettlementFacts completedFacts(SettlementSnapshot settlement) {
        if (settlement == null) {
            return null;
        }
        return new CompletedSettlementFacts(
                settlement.getSettlementId(),
                settlement.getWorkCaseId(),
                settlement.getAmount(),
                settlement.getStatus(),
                settlement.getApprovedByUserId(),
                settlement.getCompletedAt());
    }

    private void requireAllowed(SettlementPayoutDecision decision) {
        if (decision != SettlementPayoutDecision.ALLOWED) {
            throw new SettlementPayoutRejectedException(decision);
        }
    }
}
