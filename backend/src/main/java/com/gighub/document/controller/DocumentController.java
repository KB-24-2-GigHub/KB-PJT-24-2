package com.gighub.document.controller;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.auth.security.AuthPrincipals;
import com.gighub.common.api.ApiResponse;
import com.gighub.common.api.PageRequests;
import com.gighub.common.api.PageResponse;
import com.gighub.common.exception.ValidationException;
import com.gighub.document.dto.DocumentDetailResponse;
import com.gighub.document.dto.DocumentListItem;
import com.gighub.document.dto.DocumentShareItem;
import com.gighub.document.service.DocumentQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Set;

@RestController
@RequiredArgsConstructor
public class DocumentController {

    private static final Set<String> LIST_QUERY_PARAMETERS =
            Set.of("workplaceId", "docType", "page", "size");

    private final DocumentQueryService documentQueryService;

    // DOC-001: 문서 목록
    @GetMapping("/api/documents")
    public ResponseEntity<ApiResponse<PageResponse<DocumentListItem>>> getDocuments(
            @RequestParam(required = false) Long workplaceId,
            @RequestParam(required = false) String docType,
            @RequestParam(defaultValue = PageRequests.DEFAULT_PAGE_TEXT) int page,
            @RequestParam(defaultValue = PageRequests.DEFAULT_SIZE_TEXT) int size,
            Authentication authentication,
            HttpServletRequest request) {
        requireApprovedListQuery(request);
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);
        return ResponseEntity.ok(
                ApiResponse.of(documentQueryService.findDocuments(
                        principal.getUserId(),
                        principal.getRole(),
                        workplaceId,
                        docType,
                        page,
                        size)));
    }

    // DOC-003·DOC-011: 권한과 감사 Commit 뒤에만 반환하는 문서 상세
    @GetMapping("/api/documents/{documentId}")
    public ResponseEntity<ApiResponse<DocumentDetailResponse>> getDocument(
            @PathVariable Long documentId,
            @RequestParam(required = false) Long workCaseId,
            Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);
        return ResponseEntity.ok(ApiResponse.of(documentQueryService.findDocument(
                principal.getUserId(), principal.getRole(), documentId, workCaseId)));
    }

    // SHARE-002: 문서 공유 현황
    @GetMapping("/api/documents/{documentId}/shares")
    public ResponseEntity<ApiResponse<PageResponse<DocumentShareItem>>> getDocumentShares(
            @PathVariable Long documentId,
            @RequestParam(defaultValue = PageRequests.DEFAULT_PAGE_TEXT) int page,
            @RequestParam(defaultValue = PageRequests.DEFAULT_SIZE_TEXT) int size,
            Authentication authentication) {
        long actorUserId = AuthPrincipals.resolve(authentication).getUserId();
        return ResponseEntity.ok(
                ApiResponse.of(documentQueryService.findShares(
                        actorUserId, documentId, page, size)));
    }

    private void requireApprovedListQuery(HttpServletRequest request) {
        if (!LIST_QUERY_PARAMETERS.containsAll(request.getParameterMap().keySet())) {
            throw new ValidationException("지원하지 않는 문서 목록 Query입니다.");
        }
    }
}
