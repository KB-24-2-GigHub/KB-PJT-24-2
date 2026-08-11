package com.gighub.document.service;

import com.gighub.common.api.PageResponse;
import com.gighub.document.dto.DocumentListItem;
import com.gighub.document.dto.DocumentShareListResponse;
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

    DocumentShareListResponse findShares(long actorUserId, long documentId);
}
