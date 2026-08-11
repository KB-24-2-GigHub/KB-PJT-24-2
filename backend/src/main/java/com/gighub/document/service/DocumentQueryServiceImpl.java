package com.gighub.document.service;

import com.gighub.common.api.PageRequests;
import com.gighub.common.api.PageResponse;
import com.gighub.common.exception.ValidationException;
import com.gighub.document.dto.DocumentListItem;
import com.gighub.document.dto.DocumentShareItem;
import com.gighub.document.dto.DocumentShareListResponse;
import com.gighub.document.exception.DocumentNotFoundException;
import com.gighub.document.mapper.DocumentQueryMapper;
import com.gighub.document.mapper.result.DocumentListRow;
import com.gighub.document.mapper.result.DocumentShareRow;
import com.gighub.member.domain.UserRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;

/** 역할별 문서 목록과 소유자 전용 공유 이력을 안전한 공개 응답으로 변환합니다. */
@Service
public class DocumentQueryServiceImpl implements DocumentQueryService {

    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Seoul");
    private static final Set<String> DOCUMENT_TYPES =
            Set.of("EMPLOYMENT_CONTRACT", "HEALTH_CERTIFICATE");

    private final DocumentQueryMapper documentQueryMapper;
    private final Clock clock;

    @Autowired
    public DocumentQueryServiceImpl(DocumentQueryMapper documentQueryMapper) {
        this(documentQueryMapper, Clock.system(DATABASE_ZONE));
    }

    DocumentQueryServiceImpl(DocumentQueryMapper documentQueryMapper, Clock clock) {
        this.documentQueryMapper = documentQueryMapper;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DocumentListItem> findDocuments(
            long actorUserId,
            UserRole actorRole,
            Long workplaceId,
            String docType,
            int page,
            int size) {
        PageRequests.validate(page, size);
        validateFilters(workplaceId, docType);
        LocalDateTime now = LocalDateTime.now(clock);
        return PageResponse.of(
                documentQueryMapper.findDocuments(
                                actorUserId,
                                actorRole.name(),
                                workplaceId,
                                docType,
                                now,
                                now.toLocalDate(),
                                PageRequests.offset(page, size),
                                size)
                        .stream()
                        .map(this::toListItem)
                        .toList(),
                page,
                size,
                documentQueryMapper.countDocuments(
                        actorUserId,
                        actorRole.name(),
                        workplaceId,
                        docType,
                        now,
                        now.toLocalDate()));
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentShareListResponse findShares(long actorUserId, long documentId) {
        if (!documentQueryMapper.isOwnedActiveHealthDocument(documentId, actorUserId)) {
            throw new DocumentNotFoundException("문서를 찾을 수 없습니다.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        return DocumentShareListResponse.of(
                documentQueryMapper.findSharesByDocumentId(
                                documentId, now, now.toLocalDate())
                        .stream()
                        .map(this::toShareItem)
                        .toList());
    }

    private DocumentListItem toListItem(DocumentListRow row) {
        return DocumentListItem.of(
                row.getDocumentId(),
                row.getDocType(),
                row.getStatus(),
                row.getMimeType(),
                row.getIssuedDate(),
                row.getExpiresDate(),
                row.getLatestVersion(),
                row.getSource(),
                row.getOwnerName(),
                row.getSharedByName(),
                row.getWorkplaceId(),
                row.getWorkplaceName(),
                row.getWorkCaseId(),
                row.getWorkerName(),
                Boolean.TRUE.equals(row.getCanShare()),
                row.getCreatedAt());
    }

    private DocumentShareItem toShareItem(DocumentShareRow row) {
        return DocumentShareItem.of(
                row.getShareId(),
                row.getWorkplaceId(),
                row.getWorkplaceName(),
                row.getWorkCaseId(),
                row.getStatus(),
                row.getSharedAt(),
                row.getRevokedAt(),
                row.getEffectiveUntil());
    }

    private void validateFilters(Long workplaceId, String docType) {
        if (workplaceId != null && workplaceId <= 0) {
            throw new ValidationException(
                    "workplaceId는 양수여야 합니다.");
        }
        if (docType != null && !DOCUMENT_TYPES.contains(docType)) {
            throw new ValidationException(
                    "docType을 확인해 주세요.");
        }
    }
}
