package com.gighub.document.controller;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.auth.security.AuthPrincipals;
import com.gighub.common.exception.ValidationException;
import com.gighub.document.service.DocumentFileAccessService;
import com.gighub.document.service.DocumentFileResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 근로계약서 당사자와 보건증 소유자·유효 공유자의 파일 view·download 경계입니다(DOC-011).
 *
 * <p>Storage Key, Checksum, 실제 저장 경로는 응답에 포함하지 않습니다.</p>
 */
@RestController
@RequiredArgsConstructor
public class DocumentFileController {

    private static final Set<String> ALLOWED_MODES = Set.of("view", "download");

    private final DocumentFileAccessService documentFileAccessService;

    @GetMapping("/api/documents/{documentId}/file")
    public ResponseEntity<byte[]> getDocumentFile(
            @PathVariable Long documentId,
            @RequestParam(defaultValue = "view") String mode,
            Authentication authentication) {
        if (!ALLOWED_MODES.contains(mode)) {
            throw new ValidationException("mode는 view 또는 download여야 합니다.");
        }
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);

        DocumentFileResult result =
                documentFileAccessService.loadFile(
                        documentId,
                        principal.getUserId(),
                        principal.getRole(),
                        mode);

        String disposition = dispositionType(mode, result.isForceAttachment())
                + "; filename=\"" + result.getAsciiFileName() + "\""
                + "; filename*=UTF-8''" + encodeFileName(result.getFileName());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.ACCEPT_RANGES, "none")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(result.getMimeType()))
                .contentLength(result.getContent().length)
                .body(result.getContent());
    }

    private String dispositionType(String mode, boolean forceAttachment) {
        return forceAttachment || "download".equals(mode) ? "attachment" : "inline";
    }

    private String encodeFileName(String fileName) {
        return URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
    }

}
