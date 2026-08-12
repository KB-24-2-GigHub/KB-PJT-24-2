package com.gighub.document.service;

import com.gighub.document.mapper.result.DocumentFileAccessRow;
import com.gighub.document.storage.ContractStorageKeys;
import com.gighub.document.storage.DocumentStorageAdapter;
import com.gighub.document.storage.DocumentStorageIntegrityException;
import com.gighub.document.storage.Sha256;
import com.gighub.member.domain.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/** 짧은 DB 접근 Transaction 사이에서 잠금 없이 문서 파일을 읽고 검증합니다. */
@Service
public class DocumentFileAccessService {

    private static final Logger log = LoggerFactory.getLogger(DocumentFileAccessService.class);

    private static final String CONTRACT_DOCUMENT_TYPE = "EMPLOYMENT_CONTRACT";
    private static final String HEALTH_DOCUMENT_TYPE = "HEALTH_CERTIFICATE";
    private static final String SIGNED_VERSION_TYPE = "SIGNED";
    private static final String ORIGINAL_VERSION_TYPE = "ORIGINAL";
    private static final int CONTRACT_SIGNED_VERSION = 2;
    private static final int HEALTH_ORIGINAL_VERSION = 1;
    private static final Set<String> SAFE_MIME_TYPES =
            Set.of("application/pdf", "image/jpeg", "image/png");

    private final DocumentFileAccessTransaction accessTransaction;
    private final DocumentStorageAdapter storageAdapter;

    @Autowired
    public DocumentFileAccessService(
            DocumentFileAccessTransaction accessTransaction,
            DocumentStorageAdapter storageAdapter) {
        this.accessTransaction = accessTransaction;
        this.storageAdapter = storageAdapter;
    }

    public DocumentFileResult loadFile(
            Long documentId,
            Long actorUserId,
            UserRole actorRole,
            String mode) {
        DocumentFileAccessRow candidate = accessTransaction.prepareAccess(
                documentId, actorUserId, actorRole, mode);

        // prepare Transaction이 끝난 뒤에만 저장소 I/O를 수행해 DB 잠금을 오래 점유하지 않습니다.
        DocumentFileReadResult readResult = readVerifiedContent(candidate);
        DocumentFileAccessRow current = accessTransaction.finalizeAccess(
                candidate, readResult, actorUserId, actorRole, mode);

        boolean safeMime = SAFE_MIME_TYPES.contains(normalizedMime(current.getMimeType()));
        String responseMime = safeMime
                ? normalizedMime(current.getMimeType())
                : "application/octet-stream";
        return DocumentFileResult.builder()
                .content(readResult.getContent())
                .mimeType(responseMime)
                .fileName(fileName(current, responseMime))
                .asciiFileName(asciiFileName(current, responseMime))
                .forceAttachment(!safeMime)
                .build();
    }

    private DocumentFileReadResult readVerifiedContent(DocumentFileAccessRow row) {
        StorageKeyPlan storageKeyPlan = verifiedStorageKeyPlan(row);
        if (storageKeyPlan == null) {
            // 승인된 경로가 아니면 DB의 Key가 실제로 존재하더라도 저장소를 탐색하지 않습니다.
            return DocumentFileReadResult.fileUnavailable();
        }

        boolean checksumMismatch = false;

        try {
            if (storageAdapter.exists(storageKeyPlan.finalKey())) {
                byte[] content = storageAdapter.read(storageKeyPlan.finalKey());
                if (matchesChecksum(content, row.getChecksum())) {
                    return DocumentFileReadResult.verified(content);
                }
                checksumMismatch = true;
            }
        } catch (DocumentStorageIntegrityException failure) {
            // 저장 Key나 개인 정보가 예외 메시지에 섞일 수 있어 구조화된 이유만 기록합니다.
            log.warn("문서 파일 최종 Object를 읽지 못했습니다. result=DENIED denialReason=FILE_UNAVAILABLE");
        }

        try {
            if (storageAdapter.exists(storageKeyPlan.pendingKey())) {
                byte[] pendingContent = storageAdapter.read(storageKeyPlan.pendingKey());
                if (matchesChecksum(pendingContent, row.getChecksum())) {
                    promoteFallbackQuietly(row, storageKeyPlan);
                    return DocumentFileReadResult.verified(pendingContent);
                }
                checksumMismatch = true;
            }
        } catch (DocumentStorageIntegrityException failure) {
            log.warn("문서 파일 임시 Object를 읽지 못했습니다. result=DENIED denialReason=FILE_UNAVAILABLE");
        }

        return checksumMismatch
                ? DocumentFileReadResult.checksumMismatch()
                : DocumentFileReadResult.fileUnavailable();
    }

    private void promoteFallbackQuietly(
            DocumentFileAccessRow row,
            StorageKeyPlan storageKeyPlan) {
        try {
            storageAdapter.promote(
                    storageKeyPlan.pendingKey(),
                    storageKeyPlan.finalKey(),
                    row.getChecksum());
        } catch (RuntimeException failure) {
            // 검증된 Bytes는 반환할 수 있고 다음 조회에서 복구를 재시도할 수 있습니다.
            log.warn("문서 파일 조회 중 최종 Object 승격에 실패했습니다. result=ALLOWED");
        }
    }

    private StorageKeyPlan verifiedStorageKeyPlan(DocumentFileAccessRow row) {
        // 문서·Version·MIME·최종 Key를 한 번에 검증해 final과 pending 판단이 엇갈리지 않게 합니다.
        if (isCanonicalContractVersion(row)) {
            String finalKey = ContractStorageKeys.finalKey(
                    row.getWorkCaseId(), row.getDocumentId(), row.getVersionNo());
            return new StorageKeyPlan(
                    finalKey,
                    ContractStorageKeys.pendingKey(
                            row.getWorkCaseId(), row.getDocumentId(), row.getVersionNo()));
        }
        if (!isCanonicalHealthVersion(row)) {
            return null;
        }

        String extension = strictHealthStorageExtension(row.getMimeType());
        if (extension == null) {
            return null;
        }
        String finalKey = "health-certificates/%d/%d/v1.%s".formatted(
                row.getOwnerUserId(), row.getDocumentId(), extension);
        if (!finalKey.equals(row.getStorageKey())) {
            return null;
        }
        return new StorageKeyPlan(
                finalKey,
                "health-certificates/%d/%d/.pending/v1.%s".formatted(
                        row.getOwnerUserId(), row.getDocumentId(), extension));
    }

    private boolean isCanonicalContractVersion(DocumentFileAccessRow row) {
        if (!CONTRACT_DOCUMENT_TYPE.equals(row.getDocType())
                || !SIGNED_VERSION_TYPE.equals(row.getVersionType())
                || !"application/pdf".equals(row.getMimeType())
                || row.getWorkCaseId() == null
                || row.getWorkCaseId() <= 0
                || row.getDocumentId() == null
                || row.getDocumentId() <= 0
                || row.getVersionId() == null
                || row.getVersionId() <= 0
                || !Integer.valueOf(CONTRACT_SIGNED_VERSION).equals(row.getVersionNo())) {
            return false;
        }
        return ContractStorageKeys.finalKey(
                row.getWorkCaseId(), row.getDocumentId(), row.getVersionNo())
                .equals(row.getStorageKey());
    }

    private boolean isCanonicalHealthVersion(DocumentFileAccessRow row) {
        return HEALTH_DOCUMENT_TYPE.equals(row.getDocType())
                && ORIGINAL_VERSION_TYPE.equals(row.getVersionType())
                && Integer.valueOf(HEALTH_ORIGINAL_VERSION).equals(row.getVersionNo())
                && row.getOwnerUserId() != null
                && row.getOwnerUserId() > 0
                && row.getDocumentId() != null
                && row.getDocumentId() > 0
                && row.getVersionId() != null
                && row.getVersionId() > 0;
    }

    private String strictHealthStorageExtension(String mimeType) {
        // 응답 Header와 달리 저장 경계에서는 DB MIME 원문이 승인 문자열과 정확히 같아야 합니다.
        return switch (mimeType == null ? "" : mimeType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "application/pdf" -> "pdf";
            default -> null;
        };
    }

    private record StorageKeyPlan(String finalKey, String pendingKey) {
    }

    private boolean matchesChecksum(byte[] content, byte[] expectedChecksum) {
        return expectedChecksum != null
                && Arrays.equals(Sha256.digest(content), expectedChecksum);
    }

    private String normalizedMime(String mimeType) {
        return mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
    }

    private String fileName(DocumentFileAccessRow row, String mimeType) {
        String issuedDate = row.getIssuedDate() == null
                ? "날짜미상"
                : row.getIssuedDate().toString();
        if (HEALTH_DOCUMENT_TYPE.equals(row.getDocType())) {
            return sanitize("보건증_" + issuedDate + "_" + row.getOwnerName())
                    + extension(mimeType);
        }
        return sanitize("근로계약서_" + row.getWorkplaceName() + "_" + issuedDate
                + "_" + row.getWorkerName()) + extension(mimeType);
    }

    private String asciiFileName(DocumentFileAccessRow row, String mimeType) {
        String base = HEALTH_DOCUMENT_TYPE.equals(row.getDocType())
                ? "health-certificate"
                : "employment-contract";
        return base + extension(mimeType);
    }

    private String extension(String mimeType) {
        return switch (mimeType) {
            case "application/pdf" -> ".pdf";
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            default -> ".bin";
        };
    }

    /** 파일명에서 제어 문자와 경로 구분자를 제거해 Header 주입과 경로 오해를 막습니다. */
    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "문서";
        }
        return value.replaceAll("[\\p{Cntrl}/\\\\]+", " ").trim();
    }

}
