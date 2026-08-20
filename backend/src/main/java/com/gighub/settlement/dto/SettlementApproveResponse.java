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
    Long deductionAmount;
    Long deductionBaseMinutes;
    Long lateMinutes;
    Long earlyLeaveMinutes;
    String calculationReason;
    String calculationVersion;
    Instant calculatedAt;
    Instant completedAt;

    public static SettlementApproveResponse from(SettlementResult result) {
        validate(result);
        return SettlementApproveResponse.builder()
                .settlementId(result.getSettlementId())
                .status(result.getStatus())
                .originalEscrowAmount(result.getOriginalEscrowAmount())
                .workerPaidAmount(result.getWorkerPaidAmount())
                .ownerRefundAmount(result.getOwnerRefundAmount())
                .deductionAmount(result.getDeductionAmount())
                .deductionBaseMinutes(result.getDeductionBaseMinutes())
                .lateMinutes(result.getLateMinutes())
                .earlyLeaveMinutes(result.getEarlyLeaveMinutes())
                .calculationReason(result.getCalculationReason())
                .calculationVersion(result.getCalculationVersion())
                .calculatedAt(ApiTimes.toInstant(result.getCalculatedAt()))
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
                || result.getDeductionAmount() == null
                || result.getCompletedAt() == null
                || result.getSettlementAmount() <= 0
                || result.getOriginalEscrowAmount() <= 0
                || result.getWorkerPaidAmount() < 0
                || result.getOwnerRefundAmount() < 0
                || result.getDeductionAmount() < 0
                || !result.getSettlementAmount().equals(result.getOriginalEscrowAmount())
                || !result.getDeductionAmount().equals(result.getOwnerRefundAmount())
                || !preservesEscrow(
                        result.getOriginalEscrowAmount(),
                        result.getWorkerPaidAmount(),
                        result.getOwnerRefundAmount())
                || !matchesOutcome(result)
                || !matchesCalculationSnapshot(result)) {
            throw new IllegalStateException("정산 승인 응답 금액이 정산 원장과 일치하지 않습니다.");
        }
    }

    private static boolean matchesOutcome(SettlementResult result) {
        if ("COMPLETED".equals(result.getStatus())) {
            return true;
        }
        if ("REFUNDED".equals(result.getStatus())) {
            return result.getWorkerPaidAmount() == 0L
                    && result.getOwnerRefundAmount().equals(result.getOriginalEscrowAmount());
        }
        return false;
    }

    /**
     * 배포 전 24시간 안에 저장된 멱등 응답은 계산 필드가 없습니다.
     *
     * <p>그 Body는 금액 보존식까지 검증한 Replay에서만 허용합니다. 신규 실행이나 계산 필드
     * 일부만 있는 Body를 허용하면 Snapshot 누락을 정상 결과처럼 내보내므로 실패로 닫습니다.</p>
     */
    private static boolean matchesCalculationSnapshot(SettlementResult result) {
        boolean snapshotAbsent = result.getDeductionBaseMinutes() == null
                && result.getLateMinutes() == null
                && result.getEarlyLeaveMinutes() == null
                && result.getCalculationReason() == null
                && result.getCalculationVersion() == null
                && result.getCalculatedAt() == null;
        if (snapshotAbsent) {
            return result.isReplayed();
        }
        if (result.getCalculationReason() == null
                || result.getCalculationVersion() == null
                || result.getCalculatedAt() == null) {
            return false;
        }
        if ("LEGACY".equals(result.getCalculationReason())) {
            return "LEGACY".equals(result.getCalculationVersion())
                    && result.getDeductionBaseMinutes() == null
                    && result.getLateMinutes() == null
                    && result.getEarlyLeaveMinutes() == null;
        }
        if (!"ATTENDANCE_V1".equals(result.getCalculationVersion())
                || result.getDeductionBaseMinutes() == null
                || result.getDeductionBaseMinutes() <= 0
                || result.getLateMinutes() == null
                || result.getLateMinutes() < 0
                || result.getEarlyLeaveMinutes() == null
                || result.getEarlyLeaveMinutes() < 0) {
            return false;
        }
        return switch (result.getCalculationReason()) {
            case "CHECKED_OUT" -> "COMPLETED".equals(result.getStatus());
            case "NO_SHOW", "CHECK_OUT_MISSING" -> "REFUNDED".equals(result.getStatus());
            default -> false;
        };
    }

    private static boolean preservesEscrow(long original, long paid, long refund) {
        try {
            return Math.addExact(paid, refund) == original;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }
}
