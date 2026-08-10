package com.gighub.document.service;

import com.gighub.common.api.PageResponse;
import com.gighub.document.dto.DocumentDetailResponse;
import com.gighub.document.dto.DocumentListItem;
import com.gighub.document.dto.DocumentShareListResponse;

/** 문서 목록·상세·공유 Read Model을 권한 경계 뒤에서 제공합니다. */
public interface DocumentQueryService {

    PageResponse<DocumentListItem> findDocuments(
            long actorUserId, String documentType, int page, int size);

    DocumentDetailResponse findDocument(long actorUserId, long documentId);

    DocumentShareListResponse findShares(long actorUserId, long documentId);
}
