package com.gighub.settlement.service;

import com.gighub.settlement.domain.SettlementStatus;
import com.gighub.settlement.service.result.SettlementResult;
import com.gighub.wallet.exception.EscrowIntegrityException;
import com.gighub.wallet.service.SettlementWalletService.SettlementAmounts;
import com.gighub.work.contract.WorkCaseEscrowSnapshot;

import java.util.Objects;
import java.time.LocalDateTime;

/** 지급 뒤 다시 읽은 최소 결과와 실제 자금 이동 결과를 대사합니다. */
public final class SettlementPayoutResultValidator {

    private SettlementPayoutResultValidator() {
    }

    public static SettlementResult validateAndBuild(
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
                || settlement.workerPaidAmount() == null
                || settlement.ownerRefundAmount() == null
                || settlement.deductionBaseMinutes() == null
                || settlement.lateMinutes() == null
                || settlement.earlyLeaveMinutes() == null
                || settlement.calculationReason() == null
                || settlement.calculationVersion() == null
                || settlement.calculatedAt() == null
                || settlement.status() != SettlementStatus.COMPLETED
                || !Objects.equals(settlement.approvedByUserId(), approvedByUserId)
                || settlement.completedAt() == null) {
            throw new EscrowIntegrityException("완료된 정산의 식별 정보가 지급 명령과 일치하지 않습니다.");
        }
        validateAmounts(settlement.amount(), amounts);
        if (!settlement.workerPaidAmount().equals(amounts.workerPaidAmount())
                || !settlement.ownerRefundAmount().equals(amounts.ownerRefundAmount())) {
            throw new EscrowIntegrityException("저장된 정산 Snapshot과 실제 자금 이동 금액이 다릅니다.");
        }

        // dueAt·processingAt·completedAt·retry 조합은 Flyway lifecycle CHECK가 보장합니다.
        return SettlementResult.builder()
                .settlementId(settlement.settlementId())
                .status(settlement.status().name())
                .settlementAmount(settlement.amount())
                .originalEscrowAmount(amounts.originalEscrowAmount())
                .workerPaidAmount(amounts.workerPaidAmount())
                .ownerRefundAmount(amounts.ownerRefundAmount())
                .deductionAmount(amounts.ownerRefundAmount())
                .deductionBaseMinutes(settlement.deductionBaseMinutes())
                .lateMinutes(settlement.lateMinutes())
                .earlyLeaveMinutes(settlement.earlyLeaveMinutes())
                .calculationReason(settlement.calculationReason())
                .calculationVersion(settlement.calculationVersion())
                .calculatedAt(settlement.calculatedAt())
                .completedAt(settlement.completedAt())
                .replayed(false)
                .build();
    }

    /** 완료 대사에 필요한 최소 의미 필드만 전달합니다. */
    public record CompletedSettlementFacts(
            Long settlementId,
            Long workCaseId,
            Long amount,
            Long workerPaidAmount,
            Long ownerRefundAmount,
            Long deductionBaseMinutes,
            Long lateMinutes,
            Long earlyLeaveMinutes,
            String calculationReason,
            String calculationVersion,
            LocalDateTime calculatedAt,
            SettlementStatus status,
            Long approvedByUserId,
            LocalDateTime completedAt) {
    }

    private static void validateAmounts(long settlementAmount, SettlementAmounts amounts) {
        if (amounts == null
                || amounts.originalEscrowAmount() < 0
                || amounts.workerPaidAmount() < 0
                || amounts.ownerRefundAmount() < 0
                || settlementAmount != amounts.originalEscrowAmount()
                || !preservesEscrow(amounts)) {
            throw new EscrowIntegrityException("자금 실행 결과가 원 예치금 보존 계약과 일치하지 않습니다.");
        }
    }

    private static boolean preservesEscrow(SettlementAmounts amounts) {
        try {
            return Math.addExact(
                    amounts.workerPaidAmount(),
                    amounts.ownerRefundAmount()) == amounts.originalEscrowAmount();
        } catch (ArithmeticException overflow) {
            return false;
        }
    }
}
