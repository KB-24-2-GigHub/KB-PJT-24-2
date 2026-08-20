package com.gighub.settlement.dto;

import com.gighub.settlement.domain.SettlementStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/** MyBatis가 &lt;constructor&gt; 매핑으로 생성하므로 no-args 생성자 없이 필드를 final로 고정한다. */
@Getter
@Builder(toBuilder = true)
@AllArgsConstructor
public class SettlementSnapshot {
    private final Long settlementId;
    private final Long workCaseId;
    private final Long amount;
    private final Long workerPaidAmount;
    private final Long ownerRefundAmount;
    private final Long deductionBaseMinutes;
    private final Long lateMinutes;
    private final Long earlyLeaveMinutes;
    private final String calculationReason;
    private final String calculationVersion;
    private final LocalDateTime calculatedAt;
    private final SettlementStatus status;
    private final Long approvedByUserId;
    private final LocalDateTime dueAt;
    private final LocalDateTime processingAt;
    private final LocalDateTime completedAt;
    private final String failureCode;
    private final Integer retryCount;
    private final LocalDateTime lastFailureAt;
    private final LocalDateTime nextRetryAt;
}
