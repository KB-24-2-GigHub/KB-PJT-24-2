package com.gighub.settlement.review;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.gighub.settlement.domain.SettlementStatus;
import com.gighub.work.domain.WorkCaseStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 사용자·근무·정산 ID와 이름을 제외한 Provider 입력 Snapshot입니다. */
@Getter
@AllArgsConstructor
@JsonPropertyOrder(alphabetic = true)
public class DisputeReviewInput {
    private final String title;
    private final String content;
    private final WorkCaseStatus workCaseStatus;
    private final SettlementStatus settlementStatus;
    private final Long agreedWage;
    private final Long successfulCheckInCount;
}
