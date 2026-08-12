package com.gighub.document.dto;

import com.gighub.common.api.ApiTimes;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDateTime;

/** 저장 Key와 Checksum을 제외한 문서 상세용 Version Item입니다. */
@Getter
public final class DocumentVersionItem {

    private final Integer versionNo;
    private final String versionType;
    private final String mimeType;
    private final Long sizeBytes;
    private final Instant createdAt;

    private DocumentVersionItem(
            Integer versionNo,
            String versionType,
            String mimeType,
            Long sizeBytes,
            LocalDateTime createdAt) {
        this.versionNo = versionNo;
        this.versionType = versionType;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.createdAt = ApiTimes.toInstant(createdAt);
    }

    public static DocumentVersionItem of(
            Integer versionNo,
            String versionType,
            String mimeType,
            Long sizeBytes,
            LocalDateTime createdAt) {
        return new DocumentVersionItem(
                versionNo, versionType, mimeType, sizeBytes, createdAt);
    }
}
