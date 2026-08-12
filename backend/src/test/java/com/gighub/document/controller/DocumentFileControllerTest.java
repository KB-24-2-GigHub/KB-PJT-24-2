package com.gighub.document.controller;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.CommonExceptionHandler;
import com.gighub.document.exception.DocumentNotFoundException;
import com.gighub.document.service.DocumentFileAccessService;
import com.gighub.document.service.DocumentFileResult;
import com.gighub.member.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 파일 응답 Header와 비가시 접근의 404 통일을 검증합니다. */
@ExtendWith(MockitoExtension.class)
class DocumentFileControllerTest {

    private static final Long DOCUMENT_ID = 10L;

    @Mock
    private DocumentFileAccessService documentFileAccessService;

    private MockMvc mockMvc;
    private Authentication authentication;
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DocumentFileController(documentFileAccessService))
                .setControllerAdvice(new CommonExceptionHandler())
                .build();

        principal = new AuthPrincipal(3L, UserRole.OWNER, "김사장");
        authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    @Test
    void returnsSafeInlineHeadersForView() throws Exception {
        when(documentFileAccessService.loadFile(
                DOCUMENT_ID, 3L, UserRole.OWNER, "view"))
                .thenReturn(pdfResult(false));

        mockMvc.perform(get("/api/documents/{documentId}/file", DOCUMENT_ID)
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", allOf(
                        startsWith("inline; filename=\"employment-contract.pdf\""),
                        containsString("filename*=UTF-8''"),
                        not(containsString("contracts/")))))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("Accept-Ranges", "none"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().longValue("Content-Length", 9L))
                .andExpect(content().contentType("application/pdf"))
                .andExpect(content().bytes("PDF-BYTES".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void forcesAttachmentForUnsupportedMimeFallback() throws Exception {
        DocumentFileResult result = DocumentFileResult.builder()
                .content(new byte[]{1})
                .mimeType("application/octet-stream")
                .fileName("문서.bin")
                .asciiFileName("document.bin")
                .forceAttachment(true)
                .build();
        when(documentFileAccessService.loadFile(
                DOCUMENT_ID, 3L, UserRole.OWNER, "view"))
                .thenReturn(result);

        mockMvc.perform(get("/api/documents/{documentId}/file", DOCUMENT_ID)
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("attachment;")))
                .andExpect(content().contentType("application/octet-stream"));
    }

    @Test
    void downloadUsesAttachment() throws Exception {
        when(documentFileAccessService.loadFile(
                DOCUMENT_ID, 3L, UserRole.OWNER, "download"))
                .thenReturn(pdfResult(false));

        mockMvc.perform(get("/api/documents/{documentId}/file", DOCUMENT_ID)
                        .param("mode", "download")
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("attachment;")));
    }

    @Test
    void rejectsUnknownModeBeforeServiceCall() throws Exception {
        mockMvc.perform(get("/api/documents/{documentId}/file", DOCUMENT_ID)
                        .param("mode", "edit")
                        .principal(authentication))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(documentFileAccessService, never()).loadFile(any(), any(), any(), any());
    }

    @Test
    void everyInvisibleDocumentIsReturnedAsNotFound() throws Exception {
        when(documentFileAccessService.loadFile(
                DOCUMENT_ID, 3L, UserRole.OWNER, "view"))
                .thenThrow(new DocumentNotFoundException("문서를 찾을 수 없습니다."));

        mockMvc.perform(get("/api/documents/{documentId}/file", DOCUMENT_ID)
                        .principal(authentication))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void rejectsRequestWithoutSession() throws Exception {
        mockMvc.perform(get("/api/documents/{documentId}/file", DOCUMENT_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verify(documentFileAccessService, never()).loadFile(any(), any(), any(), any());
    }

    private DocumentFileResult pdfResult(boolean forceAttachment) {
        return DocumentFileResult.builder()
                .content("PDF-BYTES".getBytes(StandardCharsets.UTF_8))
                .mimeType("application/pdf")
                .fileName("근로계약서_강남점_2026-08-11_김근로.pdf")
                .asciiFileName("employment-contract.pdf")
                .forceAttachment(forceAttachment)
                .build();
    }
}
