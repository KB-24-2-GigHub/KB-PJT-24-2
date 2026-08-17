package com.gighub.settlement.mapper.result;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 잠금 없는 배치 조회가 비동기 선점 Transaction에 넘기는 내부 식별자입니다. */
@Getter
@AllArgsConstructor
public class DisputeReviewCandidate {
    private final Long reviewId;
    private final Long disputeId;
    private final Long workCaseId;
}
