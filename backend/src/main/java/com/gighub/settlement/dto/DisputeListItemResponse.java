package com.gighub.settlement.dto;

import com.gighub.member.domain.UserRole;
import com.gighub.settlement.domain.DisputeStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

/** 내부 사용자와 처리자 ID를 노출하지 않는 분쟁 목록 Item입니다. */
@Getter
@AllArgsConstructor
public class DisputeListItemResponse {
    private final Long reportId;
    private final String title;
    private final String content;
    private final DisputeStatus status;
    private final String resolution;
    private final UserRole requesterRole;
    private final Instant createdAt;
    private final Instant resolvedAt;
    private final DisputeDemoReviewResponse demoReview;
}
