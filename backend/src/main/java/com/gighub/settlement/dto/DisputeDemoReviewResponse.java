package com.gighub.settlement.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 외부 법률 판단이 아닌 DEMO Provider의 검토 결과입니다. */
@Getter
@AllArgsConstructor
public class DisputeDemoReviewResponse {
    private final String source;
    private final String status;
    private final String decision;
    private final List<String> reasonCodes;
    private final String summary;
    private final BigDecimal confidence;
    private final Instant reviewedAt;
}
