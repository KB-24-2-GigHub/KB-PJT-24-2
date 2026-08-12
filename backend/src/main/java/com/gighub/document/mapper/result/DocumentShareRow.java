package com.gighub.document.mapper.result;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** 보건증 소유자에게 보여 줄 공유 이력의 내부 Projection입니다. */
@Getter
@Builder
@AllArgsConstructor
public class DocumentShareRow {

    private final Long shareId;
    private final Long workplaceId;
    private final String workplaceName;
    private final Long workCaseId;
    private final String status;
    private final LocalDateTime sharedAt;
    private final LocalDateTime revokedAt;
    private final LocalDateTime effectiveUntil;
}
