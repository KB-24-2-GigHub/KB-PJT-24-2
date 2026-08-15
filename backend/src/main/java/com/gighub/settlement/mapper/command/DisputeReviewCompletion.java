package com.gighub.settlement.mapper.command;

import com.gighub.settlement.review.DisputeReviewDecision;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/** 검증을 마친 Provider 결과만 감사 행에 완료 상태로 저장합니다. */
@Getter
@Builder
public class DisputeReviewCompletion {
    private final Long reviewId;
    private final String requestKey;
    private final DisputeReviewDecision decision;
    private final String reasonCodesJson;
    private final String summary;
    private final BigDecimal confidence;
    private final String providerResponseId;
}
