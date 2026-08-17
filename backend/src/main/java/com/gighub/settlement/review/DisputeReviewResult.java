package com.gighub.settlement.review;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/** 금액 명령을 포함할 수 없는 닫힌 DEMO 검토 결과입니다. */
@Getter
@AllArgsConstructor
public class DisputeReviewResult {
    private final DisputeReviewDecision decision;
    private final List<String> reasonCodes;
    private final String summary;
    private final BigDecimal confidence;
}
