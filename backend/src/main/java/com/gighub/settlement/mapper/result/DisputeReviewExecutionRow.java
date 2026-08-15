package com.gighub.settlement.mapper.result;

import com.gighub.settlement.review.DisputeReviewDecision;
import com.gighub.settlement.review.DisputeReviewExecutionStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 중복·지연 응답을 판정할 때 잠그는 AI 검토 감사 행입니다. */
@Getter
@AllArgsConstructor
public class DisputeReviewExecutionRow {
    private final Long reviewId;
    private final Long disputeId;
    private final String requestKey;
    private final DisputeReviewExecutionStatus status;
    private final String source;
    private final String provider;
    private final String model;
    private final String promptVersion;
    private final String inputHash;
    private final DisputeReviewDecision decision;
    private final String reasonCodesJson;
    private final String summary;
    private final BigDecimal confidence;
    private final String providerResponseId;
    private final String failureCode;
    private final LocalDateTime leaseUntil;
    private final LocalDateTime startedAt;
    private final LocalDateTime completedAt;
}
