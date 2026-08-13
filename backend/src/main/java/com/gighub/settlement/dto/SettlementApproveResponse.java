package com.gighub.settlement.dto;

import com.gighub.common.api.ApiTimes;
import com.gighub.settlement.service.result.SettlementResult;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class SettlementApproveResponse {
    Long settlementId;
    String status;
    Long originalEscrowAmount;
    Long workerPaidAmount;
    Long ownerRefundAmount;
    Instant completedAt;

    public static SettlementApproveResponse from(SettlementResult result) {
        validate(result);
        return SettlementApproveResponse.builder()
                .settlementId(result.getSettlementId())
                .status(result.getStatus())
                .originalEscrowAmount(result.getOriginalEscrowAmount())
                .workerPaidAmount(result.getWorkerPaidAmount())
                .ownerRefundAmount(result.getOwnerRefundAmount())
                .completedAt(ApiTimes.toInstant(result.getCompletedAt()))
                .build();
    }

    private static void validate(SettlementResult result) {
        if (result == null
                || result.getSettlementId() == null
                || result.getSettlementId() <= 0
                || result.getSettlementAmount() == null
                || result.getOriginalEscrowAmount() == null
                || result.getWorkerPaidAmount() == null
                || result.getOwnerRefundAmount() == null
                || result.getCompletedAt() == null
                || result.getSettlementAmount() <= 0
                || result.getOriginalEscrowAmount() <= 0
                || result.getWorkerPaidAmount() < 0
                || result.getOwnerRefundAmount() < 0
                || !result.getSettlementAmount().equals(result.getOriginalEscrowAmount())
                || !preservesEscrow(
                        result.getOriginalEscrowAmount(),
                        result.getWorkerPaidAmount(),
                        result.getOwnerRefundAmount())
                || !matchesOutcome(result)) {
            throw new IllegalStateException("정산 승인 응답 금액이 정산 원장과 일치하지 않습니다.");
        }
    }

    private static boolean matchesOutcome(SettlementResult result) {
        if ("COMPLETED".equals(result.getStatus())) {
            return result.getWorkerPaidAmount().equals(result.getOriginalEscrowAmount())
                    && result.getOwnerRefundAmount() == 0L;
        }
        if ("REFUNDED".equals(result.getStatus())) {
            return result.getWorkerPaidAmount() == 0L
                    && result.getOwnerRefundAmount().equals(result.getOriginalEscrowAmount());
        }
        return false;
    }

    private static boolean preservesEscrow(long original, long paid, long refund) {
        try {
            return Math.addExact(paid, refund) == original;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }
}
