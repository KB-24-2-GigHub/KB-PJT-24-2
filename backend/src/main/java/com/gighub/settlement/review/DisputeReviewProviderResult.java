package com.gighub.settlement.review;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 외부 응답 식별자와 검증된 결과를 함께 전달합니다. */
@Getter
@AllArgsConstructor
public class DisputeReviewProviderResult {
    private final String providerResponseId;
    private final DisputeReviewResult result;
}
