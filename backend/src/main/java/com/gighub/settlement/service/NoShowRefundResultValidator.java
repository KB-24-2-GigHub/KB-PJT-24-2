package com.gighub.settlement.service;

import com.gighub.settlement.domain.SettlementStatus;
import com.gighub.settlement.service.result.SettlementResult;
import com.gighub.wallet.exception.EscrowIntegrityException;
import com.gighub.wallet.service.SettlementWalletService.SettlementAmounts;
import com.gighub.work.contract.WorkCaseEscrowSnapshot;

import java.time.LocalDateTime;
import java.util.Objects;

/** 완료된 NO_SHOW 환불 결과와 실제 자금 이동 결과를 함께 검증합니다. */
public final class NoShowRefundResultValidator {

    private NoShowRefundResultValidator() {
    }

    public static SettlementResult validateAndBuild(
            RefundedSettlementFacts settlement,
            WorkCaseEscrowSnapshot work,
            Long approvedByUserId,
            SettlementAmounts amounts) {
        if (settlement == null
                || settlement.settlementId() == null
                || settlement.settlementId() <= 0
                || settlement.workCaseId() == null
                || !settlement.workCaseId().equals(work.getWorkCaseId())
                || settlement.amount() == null
                || !settlement.amount().equals(work.getAgreedWage())
                || settlement.status() != SettlementStatus.REFUNDED
                || !Objects.equals(settlement.approvedByUserId(), approvedByUserId)
                || settlement.completedAt() == null
                || amounts == null
                || amounts.originalEscrowAmount() != settlement.amount()
                || amounts.workerPaidAmount() != 0L
                || amounts.ownerRefundAmount() != settlement.amount()) {
            throw new EscrowIntegrityException("완료된 NO_SHOW 환불 결과가 승인 명령과 일치하지 않습니다.");
        }

        return SettlementResult.builder()
                .settlementId(settlement.settlementId())
                .status(settlement.status().name())
                .settlementAmount(settlement.amount())
                .originalEscrowAmount(amounts.originalEscrowAmount())
                .workerPaidAmount(amounts.workerPaidAmount())
                .ownerRefundAmount(amounts.ownerRefundAmount())
                .completedAt(settlement.completedAt())
                .replayed(false)
                .build();
    }

    public record RefundedSettlementFacts(
            Long settlementId,
            Long workCaseId,
            Long amount,
            SettlementStatus status,
            Long approvedByUserId,
            LocalDateTime completedAt) {
    }
}
