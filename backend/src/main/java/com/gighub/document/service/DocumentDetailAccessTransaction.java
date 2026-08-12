package com.gighub.document.service;

import com.gighub.document.dto.DocumentDetailResponse;
import com.gighub.document.dto.DocumentListItem;
import com.gighub.document.dto.DocumentVersionItem;
import com.gighub.document.exception.DocumentNotFoundException;
import com.gighub.document.mapper.DocumentAccessMapper;
import com.gighub.document.mapper.param.DocumentAccessLogParam;
import com.gighub.document.mapper.result.DocumentFileAccessRow;
import com.gighub.document.mapper.result.DocumentHealthShareAccessRow;
import com.gighub.member.domain.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/** 문서 상세의 권한 재검증과 접근 감사 Commit을 하나의 짧은 Transaction으로 묶습니다. */
@Service
public class DocumentDetailAccessTransaction {

    private static final Logger log =
            LoggerFactory.getLogger(DocumentDetailAccessTransaction.class);

    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Seoul");
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_EXPIRED = "EXPIRED";
    private static final String RESULT_ALLOWED = "ALLOWED";
    private static final String RESULT_DENIED = "DENIED";
    private static final String DENIAL_PARTY_ACCESS = "PARTY_ACCESS_DENIED";
    private static final String DENIAL_DOCUMENT_UNAVAILABLE = "DOCUMENT_UNAVAILABLE";
    private static final String CONTRACT_DOCUMENT_TYPE = "EMPLOYMENT_CONTRACT";
    private static final String HEALTH_DOCUMENT_TYPE = "HEALTH_CERTIFICATE";
    private static final String SOURCE_OWN = "OWN";
    private static final String SOURCE_SHARED = "SHARED";
    private static final String DETAIL_ACTION = "DOCUMENT_DETAIL_VIEW";

    private final DocumentAccessMapper documentAccessMapper;
    private final Clock clock;

    @Autowired
    public DocumentDetailAccessTransaction(DocumentAccessMapper documentAccessMapper) {
        this(documentAccessMapper, Clock.system(DATABASE_ZONE));
    }

    DocumentDetailAccessTransaction(DocumentAccessMapper documentAccessMapper, Clock clock) {
        this.documentAccessMapper = documentAccessMapper;
        this.clock = clock;
    }

    /** 감사 Commit이 끝난 뒤에만 Controller가 상세 Body를 직렬화할 수 있습니다. */
    @Transactional(noRollbackFor = DocumentNotFoundException.class)
    public DocumentDetailResponse loadDetail(
            Long documentId,
            Long actorUserId,
            UserRole actorRole,
            Long workCaseId) {
        DocumentFileAccessRow row = documentAccessMapper.lockFileAccessContext(documentId);
        if (row == null) {
            // FK 대상이 없는 ID는 DB 감사 대신 공통 traceId가 붙는 최소 보안 로그만 남깁니다.
            log.info("문서 상세 조회를 거부했습니다. result=DENIED "
                    + "denialReason=DOCUMENT_UNAVAILABLE");
            throw notFound();
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Visibility visibility = authorize(
                row, actorUserId, actorRole, workCaseId, now);
        if (!hasCompleteAllowedVersion(row)) {
            deny(row, actorUserId, DENIAL_DOCUMENT_UNAVAILABLE);
        }

        boolean canShare = SOURCE_OWN.equals(visibility.source())
                && HEALTH_DOCUMENT_TYPE.equals(row.getDocType())
                && documentAccessMapper.hasShareableHealthWorkCase(
                        row.getDocumentId(), actorUserId, now, now.toLocalDate());
        DocumentListItem item = DocumentListItem.of(
                row.getDocumentId(),
                row.getDocType(),
                externalStatus(row, now.toLocalDate()),
                row.getMimeType(),
                row.getIssuedDate(),
                row.getExpiresDate(),
                row.getVersionNo(),
                visibility.source(),
                row.getOwnerName(),
                visibility.sharedByName(),
                visibility.workplaceId(),
                visibility.workplaceName(),
                visibility.workCaseId(),
                row.getWorkerName(),
                canShare,
                row.getDocumentCreatedAt());
        DocumentVersionItem version = DocumentVersionItem.of(
                row.getVersionNo(),
                row.getVersionType(),
                row.getMimeType(),
                row.getSizeBytes(),
                row.getVersionCreatedAt());

        logAccess(row, actorUserId, RESULT_ALLOWED, null);
        return DocumentDetailResponse.of(item, List.of(version));
    }

    private Visibility authorize(
            DocumentFileAccessRow row,
            Long actorUserId,
            UserRole actorRole,
            Long workCaseId,
            LocalDateTime now) {
        if (!STATUS_ACTIVE.equals(row.getStatus()) || !isSupportedType(row.getDocType())) {
            deny(row, actorUserId, DENIAL_DOCUMENT_UNAVAILABLE);
        }

        if (CONTRACT_DOCUMENT_TYPE.equals(row.getDocType())) {
            return authorizeContract(row, actorUserId);
        }
        return authorizeHealth(row, actorUserId, actorRole, workCaseId, now);
    }

    private Visibility authorizeContract(
            DocumentFileAccessRow row,
            Long actorUserId) {
        if (actorUserId.equals(row.getContractOwnerUserId())) {
            return new Visibility(
                    SOURCE_OWN,
                    null,
                    row.getWorkplaceId(),
                    row.getWorkplaceName(),
                    row.getWorkCaseId());
        }
        if (actorUserId.equals(row.getContractWorkerUserId())) {
            return new Visibility(
                    SOURCE_SHARED,
                    row.getOwnerName(),
                    row.getWorkplaceId(),
                    row.getWorkplaceName(),
                    row.getWorkCaseId());
        }
        deny(row, actorUserId, DENIAL_PARTY_ACCESS);
        throw new IllegalStateException("도달할 수 없는 계약서 권한 분기입니다.");
    }

    private Visibility authorizeHealth(
            DocumentFileAccessRow row,
            Long actorUserId,
            UserRole actorRole,
            Long workCaseId,
            LocalDateTime now) {
        if (actorUserId.equals(row.getOwnerUserId())) {
            return new Visibility(SOURCE_OWN, null, null, null, null);
        }

        if (actorRole != UserRole.OWNER || workCaseId == null) {
            deny(row, actorUserId, DENIAL_PARTY_ACCESS);
        }

        List<DocumentHealthShareAccessRow> shares =
                documentAccessMapper.lockValidHealthShareContexts(
                        row.getDocumentId(),
                        actorUserId,
                        workCaseId,
                        now,
                        now.toLocalDate());
        if (shares.isEmpty()) {
            String reason = documentAccessMapper.hasHealthShareHistoryForWorkCase(
                    row.getDocumentId(), actorUserId, workCaseId)
                    ? DENIAL_DOCUMENT_UNAVAILABLE
                    : DENIAL_PARTY_ACCESS;
            deny(row, actorUserId, reason);
        }

        /*
         * Query의 Work Case가 상세 문맥을 이미 고정합니다. 같은 관계의 중복 공유 행이 있어도
         * 다른 관계로 fallback하지 않으며, 노출 Projection은 선택된 Work Case 한 건입니다.
         */
        DocumentHealthShareAccessRow share = shares.get(0);
        return new Visibility(
                SOURCE_SHARED,
                row.getOwnerName(),
                share.getWorkplaceId(),
                share.getWorkplaceName(),
                share.getWorkCaseId());
    }

    private boolean hasCompleteAllowedVersion(DocumentFileAccessRow row) {
        if (row.getVersionId() == null
                || row.getVersionNo() == null
                || row.getVersionType() == null
                || row.getMimeType() == null
                || row.getSizeBytes() == null
                || row.getVersionCreatedAt() == null
                || row.getDocumentCreatedAt() == null
                || row.getIssuedDate() == null
                || row.getOwnerName() == null) {
            return false;
        }
        if (CONTRACT_DOCUMENT_TYPE.equals(row.getDocType())) {
            return Objects.equals(row.getOwnerUserId(), row.getContractOwnerUserId())
                    && row.getWorkCaseId() != null
                    && row.getWorkplaceId() != null
                    && row.getWorkplaceName() != null
                    && row.getWorkerName() != null
                    && Integer.valueOf(2).equals(row.getVersionNo())
                    && "SIGNED".equals(row.getVersionType());
        }
        return row.getExpiresDate() != null
                && Integer.valueOf(1).equals(row.getVersionNo())
                && "ORIGINAL".equals(row.getVersionType());
    }

    private String externalStatus(DocumentFileAccessRow row, LocalDate today) {
        return HEALTH_DOCUMENT_TYPE.equals(row.getDocType())
                && row.getExpiresDate().isBefore(today)
                ? STATUS_EXPIRED
                : STATUS_ACTIVE;
    }

    private boolean isSupportedType(String docType) {
        return CONTRACT_DOCUMENT_TYPE.equals(docType) || HEALTH_DOCUMENT_TYPE.equals(docType);
    }

    private void deny(
            DocumentFileAccessRow row,
            Long actorUserId,
            String reason) {
        logAccess(row, actorUserId, RESULT_DENIED, reason);
        throw notFound();
    }

    private void logAccess(
            DocumentFileAccessRow row,
            Long actorUserId,
            String result,
            String denialReason) {
        int inserted = documentAccessMapper.insertAccessLog(DocumentAccessLogParam.builder()
                .documentId(row.getDocumentId())
                .documentVersionId(row.getVersionId())
                .actorUserId(actorUserId)
                .action(DETAIL_ACTION)
                .result(result)
                .denialReason(denialReason)
                .build());
        if (inserted != 1) {
            throw new IllegalStateException("문서 상세 접근 감사를 기록하지 못했습니다.");
        }
        log.info("문서 상세 조회를 처리했습니다. action={} result={} denialReason={}",
                DETAIL_ACTION, result, denialReason);
    }

    private DocumentNotFoundException notFound() {
        return new DocumentNotFoundException("문서를 찾을 수 없습니다.");
    }

    private record Visibility(
            String source,
            String sharedByName,
            Long workplaceId,
            String workplaceName,
            Long workCaseId) {

        private Visibility {
            Objects.requireNonNull(source, "source");
        }
    }
}
