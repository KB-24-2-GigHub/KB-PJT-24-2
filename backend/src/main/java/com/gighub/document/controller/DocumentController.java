package com.gighub.document.controller;

import com.gighub.auth.security.AuthPrincipals;
import com.gighub.common.api.ApiResponse;
import com.gighub.common.api.PageRequests;
import com.gighub.common.api.PageResponse;
import com.gighub.document.dto.DocumentDetailResponse;
import com.gighub.document.dto.DocumentListItem;
import com.gighub.document.dto.DocumentShareListResponse;
import com.gighub.document.service.DocumentQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentQueryService documentQueryService;

    // DOC-001: 문서 목록
    @GetMapping("/api/documents")
    public ResponseEntity<ApiResponse<PageResponse<DocumentListItem>>> getDocuments(
            @RequestParam(required = false) String documentType,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = PageRequests.DEFAULT_PAGE_TEXT) int page,
            @RequestParam(defaultValue = PageRequests.DEFAULT_SIZE_TEXT) int size,
            Authentication authentication) {
        Long loginUserId = AuthPrincipals.resolve(authentication).getUserId();
        return ResponseEntity.ok(
                ApiResponse.of(documentQueryService.findDocuments(
                        loginUserId, documentType, page, size)));
    }

    // DOC-003: 문서 메타데이터 + 버전 목록
    @GetMapping("/api/documents/{documentId}")
    public ResponseEntity<ApiResponse<DocumentDetailResponse>> getDocument(
            @PathVariable Long documentId,
            Authentication authentication) {
        long actorUserId = AuthPrincipals.resolve(authentication).getUserId();
        return ResponseEntity.ok(
                ApiResponse.of(documentQueryService.findDocument(actorUserId, documentId)));
    }

    // SHARE-002: 문서 공유 현황
    @GetMapping("/api/documents/{documentId}/shares")
    public ResponseEntity<ApiResponse<DocumentShareListResponse>> getDocumentShares(
            @PathVariable Long documentId,
            Authentication authentication) {
        long actorUserId = AuthPrincipals.resolve(authentication).getUserId();
        return ResponseEntity.ok(
                ApiResponse.of(documentQueryService.findShares(actorUserId, documentId)));
    }
}
