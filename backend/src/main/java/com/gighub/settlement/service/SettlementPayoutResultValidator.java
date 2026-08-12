package com.gighub.settlement.service;

import com.gighub.settlement.domain.SettlementStatus;
import com.gighub.settlement.service.result.SettlementResult;
import com.gighub.wallet.exception.EscrowIntegrityException;
import com.gighub.wallet.service.SettlementWalletService.SettlementAmounts;
import com.gighub.work.contract.WorkCaseEscrowSnapshot;

import java.util.Objects;
import java.time.LocalDateTime;

/** 지급 뒤 다시 읽은 최소 결과와 실제 자금 이동 결과를 대사합니다. */
public class SettlementPayoutResultValidator {

    public SettlementResult validateAndBuild(
            CompletedSettlementFacts settlement,
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
                || settlement.status() != SettlementStatus.COMPLETED
                || !Objects.equals(settlement.approvedByUserId(), approvedByUserId)
                || settlement.completedAt() == null) {
            throw new EscrowIntegrityException("완료된 정산의 식별 정보가 지급 명령과 일치하지 않습니다.");
        }
        validateAmounts(settlement.amount(), amounts);

        // dueAt·processingAt·completedAt·retry 조합은 Flyway lifecycle CHECK가 보장합니다.
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

    /** 완료 대사에 필요한 최소 의미 필드만 전달합니다. */
    public record CompletedSettlementFacts(
            Long settlementId,
            Long workCaseId,
            Long amount,
            SettlementStatus status,
            Long approvedByUserId,
            LocalDateTime completedAt) {
    }

    private void validateAmounts(long settlementAmount, SettlementAmounts amounts) {
        if (amounts == null
                || amounts.originalEscrowAmount() < 0
                || amounts.workerPaidAmount() < 0
                || amounts.ownerRefundAmount() < 0
                || settlementAmount != amounts.originalEscrowAmount()
                || !preservesEscrow(amounts)) {
            throw new EscrowIntegrityException("자금 실행 결과가 원 예치금 보존 계약과 일치하지 않습니다.");
        }
    }

    private boolean preservesEscrow(SettlementAmounts amounts) {
        try {
            return Math.addExact(
                    amounts.workerPaidAmount(),
                    amounts.ownerRefundAmount()) == amounts.originalEscrowAmount();
        } catch (ArithmeticException overflow) {
            return false;
        }
    }
}
