package com.gighub.document.mapper.result;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** 문서 목록 권한 Query가 반환하는 내부 Projection입니다. */
@Getter
@Builder
@AllArgsConstructor
public class DocumentListRow {

    private final Long documentId;
    private final String docType;
    private final String status;
    private final String mimeType;
    private final LocalDate issuedDate;
    private final LocalDate expiresDate;
    private final Integer latestVersion;
    private final String source;
    private final String ownerName;
    private final String sharedByName;
    private final Long workplaceId;
    private final String workplaceName;
    private final Long workCaseId;
    private final String workerName;
    private final Boolean canShare;
    private final LocalDateTime createdAt;
}
