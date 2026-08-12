package com.gighub.document.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

import com.gighub.common.api.ApiTimes;
import lombok.Getter;

/** 저장소 내부값을 제외한 승인 문서 목록 Item입니다. */
@Getter
public final class DocumentListItem {

    private static final String HEALTH_CERTIFICATE = "HEALTH_CERTIFICATE";

    private final Long documentId;
    private final String docType;
    private final String status;
    private final String fileName;
    private final String mimeType;
    private final LocalDate issuedDate;
    private final LocalDate expiresDate;
    private final Integer latestVersion;
    private final String source;
    private final String sharedByName;
    private final Long workplaceId;
    private final String workplaceName;
    private final Long workCaseId;
    private final Capabilities capabilities;
    private final Instant createdAt;

    private DocumentListItem(
            Long documentId,
            String docType,
            String status,
            String mimeType,
            LocalDate issuedDate,
            LocalDate expiresDate,
            Integer latestVersion,
            String source,
            String ownerName,
            String sharedByName,
            Long workplaceId,
            String workplaceName,
            Long workCaseId,
            String workerName,
            boolean canShare,
            LocalDateTime createdAt) {
        this.documentId = documentId;
        this.docType = docType;
        this.status = status;
        this.fileName = fileName(
                docType, mimeType, issuedDate, ownerName, workplaceName, workerName);
        this.mimeType = mimeType;
        this.issuedDate = issuedDate;
        this.expiresDate = expiresDate;
        this.latestVersion = latestVersion;
        this.source = source;
        this.sharedByName = sharedByName;
        this.workplaceId = workplaceId;
        this.workplaceName = workplaceName;
        this.workCaseId = workCaseId;
        this.capabilities = new Capabilities(
                true,
                true,
                canShare,
                "OWN".equals(source) && HEALTH_CERTIFICATE.equals(docType));
        this.createdAt = ApiTimes.toInstant(createdAt);
    }

    public static DocumentListItem of(
            Long documentId,
            String docType,
            String status,
            String mimeType,
            LocalDate issuedDate,
            LocalDate expiresDate,
            Integer latestVersion,
            String source,
            String ownerName,
            String sharedByName,
            Long workplaceId,
            String workplaceName,
            Long workCaseId,
            String workerName,
            boolean canShare,
            LocalDateTime createdAt) {
        return new DocumentListItem(
                documentId,
                docType,
                status,
                mimeType,
                issuedDate,
                expiresDate,
                latestVersion,
                source,
                ownerName,
                sharedByName,
                workplaceId,
                workplaceName,
                workCaseId,
                workerName,
                canShare,
                createdAt);
    }

    private static String fileName(
            String docType,
            String mimeType,
            LocalDate issuedDateValue,
            String ownerName,
            String workplaceName,
            String workerName) {
        String issuedDate = issuedDateValue == null
                ? "날짜미상" : issuedDateValue.toString();
        if (HEALTH_CERTIFICATE.equals(docType)) {
            return sanitize("보건증_" + issuedDate + "_" + ownerName)
                    + extension(mimeType);
        }
        return sanitize("근로계약서_" + workplaceName + "_" + issuedDate
                + "_" + workerName) + ".pdf";
    }

    private static String extension(String mimeType) {
        if (mimeType == null) {
            return ".bin";
        }
        return switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "application/pdf" -> ".pdf";
            default -> ".bin";
        };
    }

    /** 파일명으로 쓸 수 없는 제어문자와 경로 구분자를 사람이 읽을 수 있는 공백으로 바꿉니다. */
    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "문서";
        }
        return value.replaceAll("[\\p{Cntrl}/\\\\]+", " ").trim();
    }

    @Getter
    public static final class Capabilities {

        private final boolean canView;
        private final boolean canDownload;
        private final boolean canShare;
        private final boolean canDelete;

        private Capabilities(
                boolean canView,
                boolean canDownload,
                boolean canShare,
                boolean canDelete) {
            this.canView = canView;
            this.canDownload = canDownload;
            this.canShare = canShare;
            this.canDelete = canDelete;
        }
    }
}
