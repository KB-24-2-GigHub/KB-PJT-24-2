package com.gighub.settlement.mapper.result;

import com.gighub.member.domain.UserRole;
import com.gighub.settlement.domain.DisputeStatus;
import com.gighub.settlement.review.DisputeReviewDecision;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.math.BigDecimal;

/** 분쟁 Page 조회용 영속성 결과입니다. */
@Getter
@AllArgsConstructor
public class DisputeListRow {
    private final Long reportId;
    private final String title;
    private final String content;
    private final DisputeStatus status;
    private final String resolution;
    private final UserRole requesterRole;
    private final LocalDateTime createdAt;
    private final LocalDateTime resolvedAt;
    private final String reviewSource;
    private final DisputeReviewDecision reviewDecision;
    private final String reviewReasonCodesJson;
    private final String reviewSummary;
    private final BigDecimal reviewConfidence;
    private final LocalDateTime reviewedAt;
}
