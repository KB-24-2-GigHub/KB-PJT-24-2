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
import com.gighub.document.dto.HealthCertificateUpdateRequest;
import com.gighub.document.service.DocumentDeleteService;
import com.gighub.document.service.DocumentQueryService;
import com.gighub.document.service.HealthCertificateRegisterService;
import com.gighub.document.service.HealthCertificateUpdateService;
import com.gighub.document.validation.UploadedFile;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Set;

@RestController
@RequiredArgsConstructor
public class DocumentController {

    private static final Set<String> LIST_QUERY_PARAMETERS =
            Set.of("workplaceId", "docType", "page", "size");

    private final DocumentQueryService documentQueryService;
    private final HealthCertificateRegisterService healthCertificateRegisterService;
    private final HealthCertificateUpdateService healthCertificateUpdateService;
    private final DocumentDeleteService documentDeleteService;

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

    // DOC-005: 보건증 등록
    @PostMapping("/api/documents")
    public ResponseEntity<ApiResponse<DocumentListItem>> registerHealthCertificate(
            @RequestParam String docType,
            @RequestParam MultipartFile file,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issuedDate,
            Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);
        DocumentListItem registered = healthCertificateRegisterService.register(
                principal, docType, issuedDate, toUploadedFile(file));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(registered));
    }

    private UploadedFile toUploadedFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return new UploadedFile(new byte[0], null, null);
        }
        try {
            return new UploadedFile(file.getBytes(), file.getOriginalFilename(), file.getContentType());
        } catch (IOException e) {
            throw new ValidationException("보건증 파일을 읽을 수 없습니다.", "file", "UNREADABLE");
        }
    }

    // DOC-006: 보건증 발급일 수정
    @PatchMapping("/api/documents/{documentId}")
    public ResponseEntity<ApiResponse<DocumentListItem>> updateHealthCertificate(
            @PathVariable long documentId,
            @Valid @RequestBody HealthCertificateUpdateRequest request,
            Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);
        DocumentListItem updated = healthCertificateUpdateService.updateIssuedDate(
                principal, documentId, request.getIssuedDate());
        return ResponseEntity.ok(ApiResponse.of(updated));
    }

    // DOC-006: 보건증 논리 삭제(근로계약서는 409로 거부)
    @DeleteMapping("/api/documents/{documentId}")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable long documentId, Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);
        documentDeleteService.delete(principal, documentId);
        return ResponseEntity.noContent().build();
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
