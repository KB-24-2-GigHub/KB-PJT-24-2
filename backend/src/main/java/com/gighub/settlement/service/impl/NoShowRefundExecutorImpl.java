package com.gighub.settlement.service.impl;

import com.gighub.settlement.dto.SettlementSnapshot;
import com.gighub.settlement.mapper.SettlementMapper;
import com.gighub.settlement.service.NoShowRefundExecutor;
import com.gighub.settlement.service.NoShowRefundResultValidator.RefundedSettlementFacts;
import com.gighub.settlement.service.policy.NoShowRefundPolicy;
import com.gighub.settlement.service.policy.NoShowRefundPolicy.RefundSettlementFacts;
import com.gighub.settlement.service.policy.SettlementPayoutDecision;
import com.gighub.settlement.service.policy.SettlementPayoutRejectedException;
import com.gighub.settlement.service.result.SettlementResult;
import com.gighub.wallet.exception.EscrowIntegrityException;
import com.gighub.wallet.idempotency.WalletIdempotencyKeys;
import com.gighub.wallet.service.SettlementWalletService;
import com.gighub.wallet.service.SettlementWalletService.SettlementAmounts;
import com.gighub.wallet.service.SettlementWalletService.SettlementWalletLock;
import com.gighub.wallet.service.command.NoShowRefundWalletCommand;
import com.gighub.wallet.service.result.SettlementEscrowSnapshot;
import com.gighub.work.contract.WorkCaseEscrowSnapshot;
import com.gighub.work.service.WorkSettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.gighub.settlement.service.NoShowRefundResultValidator.validateAndBuild;

/** NO_SHOW 환불을 Work → Settlement → Dispute → Escrow → Wallet 잠금 순서로 실행합니다. */
@Service
@RequiredArgsConstructor
public class NoShowRefundExecutorImpl implements NoShowRefundExecutor {

    private final SettlementMapper settlementMapper;
    private final WorkSettlementService workSettlementService;
    private final SettlementWalletService settlementWalletService;
    private final NoShowRefundPolicy refundPolicy = new NoShowRefundPolicy();

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SettlementResult execute(long workCaseId, long ownerUserId) {
        WorkCaseEscrowSnapshot work = workSettlementService.lockEscrowContext(workCaseId);
        requireAllowed(refundPolicy.assessWork(workCaseId, ownerUserId, work));

        SettlementSnapshot settlement = settlementMapper.findByWorkCaseIdForUpdate(workCaseId);
        RefundSettlementFacts settlementFacts = facts(settlement);
        requireAllowed(refundPolicy.assessSettlement(
                workCaseId, ownerUserId, work, settlementFacts));

        boolean hasBlockingDispute = !settlementMapper.findBlockingDisputeIdsForUpdate(
                workCaseId).isEmpty();
        NoShowRefundWalletCommand walletCommand = walletCommand(work, settlement);
        SettlementEscrowSnapshot escrow = settlementWalletService.lockRefundEscrow(walletCommand);
        requireAllowed(refundPolicy.assessRefund(
                workCaseId,
                ownerUserId,
                work,
                settlementFacts,
                escrow,
                hasBlockingDispute));

        SettlementWalletLock walletLock =
                settlementWalletService.lockRefundWallet(walletCommand, escrow.getEscrowId());
        settlementWalletService.verifyHeldRefundEscrow(
                walletCommand, escrow.getEscrowId(), walletLock);

        if (settlementMapper.transitionWaitingToRefundProcessing(
                settlement.getSettlementId(), ownerUserId) != 1) {
            throw new EscrowIntegrityException("정산을 NO_SHOW 환불 처리 중 상태로 전환하지 못했습니다.");
        }

        // Escrow, OWNER Wallet, 원장, Settlement는 이 바깥 Transaction과 함께만 확정됩니다.
        SettlementAmounts amounts = settlementWalletService.refund(
                walletCommand, escrow.getEscrowId(), walletLock);
        if (settlementMapper.transitionRefundProcessingToRefunded(
                settlement.getSettlementId(), ownerUserId) != 1) {
            throw new EscrowIntegrityException("정산을 NO_SHOW 환불 완료 상태로 전환하지 못했습니다.");
        }

        SettlementSnapshot refunded = settlementMapper.findByWorkCaseIdForUpdate(workCaseId);
        settlementWalletService.verifyCompletedRefund(walletCommand, walletLock);
        return validateAndBuild(refundedFacts(refunded), work, ownerUserId, amounts);
    }

    private NoShowRefundWalletCommand walletCommand(
            WorkCaseEscrowSnapshot work, SettlementSnapshot settlement) {
        if (settlement == null || settlement.getSettlementId() == null) {
            throw new EscrowIntegrityException("NO_SHOW 환불 대상 정산 정보가 없습니다.");
        }
        return NoShowRefundWalletCommand.builder()
                .workCaseId(work.getWorkCaseId())
                .employerId(work.getEmployerId())
                .amount(work.getAgreedWage())
                .employerLedgerKey(WalletIdempotencyKeys.settlementRefundOwner(
                        settlement.getSettlementId()))
                .build();
    }

    private RefundSettlementFacts facts(SettlementSnapshot settlement) {
        if (settlement == null) {
            return null;
        }
        return new RefundSettlementFacts(
                settlement.getSettlementId(),
                settlement.getWorkCaseId(),
                settlement.getAmount(),
                settlement.getStatus(),
                settlement.getDueAt());
    }

    private RefundedSettlementFacts refundedFacts(SettlementSnapshot settlement) {
        if (settlement == null) {
            return null;
        }
        return new RefundedSettlementFacts(
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
