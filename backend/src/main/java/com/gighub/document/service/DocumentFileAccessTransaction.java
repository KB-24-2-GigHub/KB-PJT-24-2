package com.gighub.document.service;

import com.gighub.document.exception.DocumentNotFoundException;
import com.gighub.document.mapper.DocumentAccessMapper;
import com.gighub.document.mapper.param.DocumentAccessLogParam;
import com.gighub.document.mapper.result.DocumentFileAccessRow;
import com.gighub.document.storage.DocumentStorageIntegrityException;
import com.gighub.member.domain.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Objects;

/** 문서 파일 접근의 짧은 잠금·재검증·감사 Transaction을 소유합니다. */
@Service
public class DocumentFileAccessTransaction {

    private static final Logger log =
            LoggerFactory.getLogger(DocumentFileAccessTransaction.class);

    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Seoul");
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String RESULT_ALLOWED = "ALLOWED";
    private static final String RESULT_DENIED = "DENIED";
    private static final String DENIAL_PARTY_ACCESS = "PARTY_ACCESS_DENIED";
    private static final String DENIAL_DOCUMENT_UNAVAILABLE = "DOCUMENT_UNAVAILABLE";
    private static final String DENIAL_SIGNED_VERSION_UNAVAILABLE =
            "SIGNED_VERSION_UNAVAILABLE";
    private static final String DENIAL_FILE_UNAVAILABLE = "FILE_UNAVAILABLE";
    private static final String DENIAL_CHECKSUM_MISMATCH = "CHECKSUM_MISMATCH";
    private static final String CONTRACT_DOCUMENT_TYPE = "EMPLOYMENT_CONTRACT";
    private static final String HEALTH_DOCUMENT_TYPE = "HEALTH_CERTIFICATE";

    private final DocumentAccessMapper documentAccessMapper;
    private final Clock clock;

    @Autowired
    public DocumentFileAccessTransaction(DocumentAccessMapper documentAccessMapper) {
        this(documentAccessMapper, Clock.system(DATABASE_ZONE));
    }

    DocumentFileAccessTransaction(DocumentAccessMapper documentAccessMapper, Clock clock) {
        this.documentAccessMapper = documentAccessMapper;
        this.clock = clock;
    }

    /** 저장소를 읽기 전에 현재 접근 권한과 허용 Version을 짧게 잠가 확인합니다. */
    @Transactional(noRollbackFor = {
            DocumentNotFoundException.class,
            DocumentStorageIntegrityException.class
    })
    public DocumentFileAccessRow prepareAccess(
            Long documentId,
            Long actorUserId,
            UserRole actorRole,
            String mode) {
        DocumentFileAccessRow row = lockContext(documentId);
        validateAccess(row, actorUserId, actorRole, mode, LocalDateTime.now(clock));
        return row;
    }

    /**
     * 저장소 검증 뒤 권한과 Version을 새 시각으로 다시 확인하고 접근 감사를 Commit합니다.
     *
     * <p>이 메서드는 별도 Spring Bean의 Transaction 경계이므로 반환이 끝날 때 감사 Commit도
     * 완료됩니다. 감사 실패 시 호출자는 파일 내용을 응답 객체로 만들 수 없습니다.</p>
     */
    @Transactional(noRollbackFor = {
            DocumentNotFoundException.class,
            DocumentStorageIntegrityException.class
    })
    public DocumentFileAccessRow finalizeAccess(
            DocumentFileAccessRow candidate,
            DocumentFileReadResult readResult,
            Long actorUserId,
            UserRole actorRole,
            String mode) {
        DocumentFileAccessRow current = lockContext(candidate.getDocumentId());
        validateAccess(current, actorUserId, actorRole, mode, LocalDateTime.now(clock));

        // 잠금 밖에서 읽은 Bytes가 지금도 같은 불변 Version의 것인지 확인한 뒤에만 허용합니다.
        if (!sameVersion(candidate, current)) {
            denyIntegrity(current, actorUserId, mode, DENIAL_FILE_UNAVAILABLE);
        }
        if (!readResult.isVerified()) {
            String reason = readResult.getStatus()
                    == DocumentFileReadResult.Status.CHECKSUM_MISMATCH
                    ? DENIAL_CHECKSUM_MISMATCH
                    : DENIAL_FILE_UNAVAILABLE;
            denyIntegrity(current, actorUserId, mode, reason);
        }

        logAccess(current, actorUserId, mode, RESULT_ALLOWED, null);
        return current;
    }

    private DocumentFileAccessRow lockContext(Long documentId) {
        DocumentFileAccessRow row = documentAccessMapper.lockFileAccessContext(documentId);
        if (row == null) {
            // 존재하지 않는 문서는 FK 대상이 없으므로 DB 감사 없이 MDC의 traceId로만 추적합니다.
            log.info("문서 파일 접근을 거부했습니다. result=DENIED denialReason=DOCUMENT_UNAVAILABLE");
            throw notFound();
        }
        return row;
    }

    private void validateAccess(
            DocumentFileAccessRow row,
            Long actorUserId,
            UserRole actorRole,
            String mode,
            LocalDateTime now) {
        if (!STATUS_ACTIVE.equals(row.getStatus()) || !isSupportedDocumentType(row.getDocType())) {
            denyInvisible(row, actorUserId, mode, DENIAL_DOCUMENT_UNAVAILABLE);
        }

        // 비당사자에게 Version 누락 여부가 500 응답으로 드러나지 않도록 권한을 먼저 판정합니다.
        authorize(row, actorUserId, actorRole, mode, now);
        if (row.getVersionId() == null) {
            String reason = CONTRACT_DOCUMENT_TYPE.equals(row.getDocType())
                    ? DENIAL_SIGNED_VERSION_UNAVAILABLE
                    : DENIAL_FILE_UNAVAILABLE;
            denyIntegrity(row, actorUserId, mode, reason);
        }
    }

    private void authorize(
            DocumentFileAccessRow row,
            Long actorUserId,
            UserRole actorRole,
            String mode,
            LocalDateTime now) {
        if (CONTRACT_DOCUMENT_TYPE.equals(row.getDocType())) {
            boolean party = actorUserId.equals(row.getContractOwnerUserId())
                    || actorUserId.equals(row.getContractWorkerUserId());
            if (!party) {
                denyInvisible(row, actorUserId, mode, DENIAL_PARTY_ACCESS);
            }
            return;
        }

        if (actorUserId.equals(row.getOwnerUserId())) {
            // 만료된 보건증도 소유자는 이력 확인과 교체를 위해 계속 열람할 수 있습니다.
            return;
        }

        Long shareId = null;
        if (actorRole == UserRole.OWNER) {
            shareId = documentAccessMapper.lockValidHealthShare(
                    row.getDocumentId(), actorUserId, now, now.toLocalDate());
        }
        if (shareId == null) {
            String reason = documentAccessMapper.hasHealthShareHistory(
                    row.getDocumentId(), actorUserId)
                    ? DENIAL_DOCUMENT_UNAVAILABLE
                    : DENIAL_PARTY_ACCESS;
            denyInvisible(row, actorUserId, mode, reason);
        }
    }

    private boolean sameVersion(
            DocumentFileAccessRow candidate,
            DocumentFileAccessRow current) {
        return Objects.equals(candidate.getDocumentId(), current.getDocumentId())
                && Objects.equals(candidate.getOwnerUserId(), current.getOwnerUserId())
                && Objects.equals(candidate.getWorkCaseId(), current.getWorkCaseId())
                && Objects.equals(candidate.getDocType(), current.getDocType())
                && Objects.equals(candidate.getVersionId(), current.getVersionId())
                && Objects.equals(candidate.getVersionNo(), current.getVersionNo())
                && Objects.equals(candidate.getVersionType(), current.getVersionType())
                && Objects.equals(candidate.getStorageKey(), current.getStorageKey())
                && Objects.equals(candidate.getMimeType(), current.getMimeType())
                && Arrays.equals(candidate.getChecksum(), current.getChecksum())
                && Objects.equals(
                        candidate.getContractOwnerUserId(), current.getContractOwnerUserId())
                && Objects.equals(
                        candidate.getContractWorkerUserId(), current.getContractWorkerUserId());
    }

    private void denyInvisible(
            DocumentFileAccessRow row,
            Long actorUserId,
            String mode,
            String reason) {
        logAccess(row, actorUserId, mode, RESULT_DENIED, reason);
        throw notFound();
    }

    private void denyIntegrity(
            DocumentFileAccessRow row,
            Long actorUserId,
            String mode,
            String reason) {
        logAccess(row, actorUserId, mode, RESULT_DENIED, reason);
        throw new DocumentStorageIntegrityException("문서 파일을 안전하게 제공할 수 없습니다.");
    }

    private void logAccess(
            DocumentFileAccessRow row,
            Long actorUserId,
            String mode,
            String result,
            String denialReason) {
        int inserted = documentAccessMapper.insertAccessLog(DocumentAccessLogParam.builder()
                .documentId(row.getDocumentId())
                .documentVersionId(row.getVersionId())
                .actorUserId(actorUserId)
                .action(actionFor(row.getDocType(), mode))
                .result(result)
                .denialReason(denialReason)
                .build());
        if (inserted != 1) {
            throw new IllegalStateException("문서 접근 감사를 기록하지 못했습니다.");
        }
        log.info("문서 파일 접근을 처리했습니다. action={} result={} denialReason={}",
                actionFor(row.getDocType(), mode), result, denialReason);
    }

    private String actionFor(String docType, String mode) {
        String prefix = HEALTH_DOCUMENT_TYPE.equals(docType)
                ? "HEALTH_CERT_FILE_"
                : "CONTRACT_FILE_";
        return prefix + ("download".equals(mode) ? "DOWNLOAD" : "VIEW");
    }

    private boolean isSupportedDocumentType(String docType) {
        return CONTRACT_DOCUMENT_TYPE.equals(docType) || HEALTH_DOCUMENT_TYPE.equals(docType);
    }

    private DocumentNotFoundException notFound() {
        return new DocumentNotFoundException("문서를 찾을 수 없습니다.");
    }
}
