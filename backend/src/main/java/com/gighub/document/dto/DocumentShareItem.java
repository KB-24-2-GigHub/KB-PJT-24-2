package com.gighub.document.dto;

import java.time.Instant;
import java.time.LocalDateTime;

import com.gighub.common.api.ApiTimes;
import lombok.Getter;

/** 공유 대상 사용자 ID를 포함하지 않는 보건증 공유 이력 Item입니다. */
@Getter
public final class DocumentShareItem {

    private final Long shareId;
    private final Long workplaceId;
    private final String workplaceName;
    private final Long workCaseId;
    private final String status;
    private final Instant sharedAt;
    private final Instant revokedAt;
    private final Instant effectiveUntil;

    private DocumentShareItem(
            Long shareId,
            Long workplaceId,
            String workplaceName,
            Long workCaseId,
            String status,
            LocalDateTime sharedAt,
            LocalDateTime revokedAt,
            LocalDateTime effectiveUntil) {
        this.shareId = shareId;
        this.workplaceId = workplaceId;
        this.workplaceName = workplaceName;
        this.workCaseId = workCaseId;
        this.status = status;
        this.sharedAt = ApiTimes.toInstant(sharedAt);
        this.revokedAt = ApiTimes.toInstant(revokedAt);
        this.effectiveUntil = ApiTimes.toInstant(effectiveUntil);
    }

    public static DocumentShareItem of(
            Long shareId,
            Long workplaceId,
            String workplaceName,
            Long workCaseId,
            String status,
            LocalDateTime sharedAt,
            LocalDateTime revokedAt,
            LocalDateTime effectiveUntil) {
        return new DocumentShareItem(
                shareId,
                workplaceId,
                workplaceName,
                workCaseId,
                status,
                sharedAt,
                revokedAt,
                effectiveUntil);
    }
}
