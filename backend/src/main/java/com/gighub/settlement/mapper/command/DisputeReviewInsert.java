package com.gighub.settlement.mapper.command;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/** 분쟁 생성 Transaction에서 함께 저장하는 비동기 검토 요청입니다. */
@Getter
@Builder
public class DisputeReviewInsert {
    @Setter
    private Long reviewId;
    private final Long disputeId;
    private final String requestKey;
    private final String provider;
    private final String model;
    private final String promptVersion;
    private final String inputHash;
}
