package com.gighub.document.service;

import com.gighub.common.api.PageRequests;
import com.gighub.common.api.PageResponse;
import com.gighub.document.dto.Document;
import com.gighub.document.dto.DocumentDetailResponse;
import com.gighub.document.dto.DocumentListItem;
import com.gighub.document.dto.DocumentShareListResponse;
import com.gighub.document.exception.DocumentNotFoundException;
import com.gighub.document.mapper.DocumentQueryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 기존 문서 조회 동작을 유지하면서 Controller의 Mapper 접근을 제거합니다. */
@Service
public class DocumentQueryServiceImpl implements DocumentQueryService {

    private final DocumentQueryMapper documentQueryMapper;

    public DocumentQueryServiceImpl(DocumentQueryMapper documentQueryMapper) {
        this.documentQueryMapper = documentQueryMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DocumentListItem> findDocuments(
            long actorUserId, String documentType, int page, int size) {
        PageRequests.validate(page, size);
        return PageResponse.of(
                documentQueryMapper.findDocuments(
                        actorUserId, documentType, PageRequests.offset(page, size), size),
                page,
                size,
                documentQueryMapper.countDocuments(actorUserId, documentType));
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentDetailResponse findDocument(long actorUserId, long documentId) {
        Document document = documentQueryMapper.findDocumentById(documentId);
        if (document == null) {
            throw new DocumentNotFoundException("문서를 찾을 수 없습니다.");
        }
        // #132가 소유하는 접근 권한·감사 정책은 이 구조 변경에서 임의 구현하지 않습니다.
        return DocumentDetailResponse.of(
                document, documentQueryMapper.findVersionsByDocumentId(documentId));
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentShareListResponse findShares(long actorUserId, long documentId) {
        // #132가 소유하는 소유자 검증은 이 구조 변경에서 임의 구현하지 않습니다.
        return DocumentShareListResponse.of(
                documentQueryMapper.findSharesByDocumentId(documentId));
    }
}
