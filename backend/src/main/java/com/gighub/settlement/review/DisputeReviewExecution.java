package com.gighub.settlement.review;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 짧은 선점 Transaction이 외부 호출 단계로 넘기는 불변 실행값입니다. */
@Getter
@AllArgsConstructor
public class DisputeReviewExecution {
    private final Long reviewId;
    private final Long disputeId;
    private final Long workCaseId;
    private final String requestKey;
    private final String inputHash;
    private final DisputeReviewInput input;
}
