package com.gighub.document.mapper.result;

import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** 파일 권한 판정과 무결성 검증에만 사용하는 내부 Projection입니다. */
@Getter
@Builder
@AllArgsConstructor
public class DocumentFileAccessRow {

    private final Long documentId;
    private final Long ownerUserId;
    private final Long workCaseId;
    private final String docType;
    private final String status;
    private final LocalDate issuedDate;
    private final LocalDate expiresDate;
    private final Long versionId;
    private final Integer versionNo;
    private final String versionType;
    private final String storageKey;
    private final String mimeType;
    private final byte[] checksum;
    private final Long contractOwnerUserId;
    private final Long contractWorkerUserId;
    private final String ownerName;
    private final String workerName;
    private final Long workplaceId;
    private final String workplaceName;
}
