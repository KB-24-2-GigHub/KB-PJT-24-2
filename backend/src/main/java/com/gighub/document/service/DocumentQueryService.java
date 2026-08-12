package com.gighub.document.service;

import com.gighub.common.api.PageResponse;
import com.gighub.document.dto.DocumentDetailResponse;
import com.gighub.document.dto.DocumentListItem;
import com.gighub.document.dto.DocumentShareItem;
import com.gighub.member.domain.UserRole;

/** 문서 목록과 보건증 공유 이력을 권한 경계 뒤에서 제공합니다. */
public interface DocumentQueryService {

    PageResponse<DocumentListItem> findDocuments(
            long actorUserId,
            UserRole actorRole,
            Long workplaceId,
            String docType,
            int page,
            int size);

    DocumentDetailResponse findDocument(
            long actorUserId,
            UserRole actorRole,
            long documentId,
            Long workCaseId);

    PageResponse<DocumentShareItem> findShares(
            long actorUserId,
            long documentId,
            int page,
            int size);
}
