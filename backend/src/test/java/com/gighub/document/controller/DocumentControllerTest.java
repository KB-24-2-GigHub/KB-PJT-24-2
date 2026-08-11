package com.gighub.document.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.api.PageResponse;
import com.gighub.common.exception.CommonExceptionHandler;
import com.gighub.document.dto.DocumentListItem;
import com.gighub.document.dto.DocumentShareItem;
import com.gighub.document.dto.DocumentShareListResponse;
import com.gighub.document.service.DocumentQueryService;
import com.gighub.member.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 문서 목록과 공유 조회가 승인된 공개 응답만 노출하는지 검증합니다. */
@ExtendWith(MockitoExtension.class)
class DocumentControllerTest {

    private static final Long USER_ID = 7L;
    private static final Long DOCUMENT_ID = 11L;

    @Mock
    private DocumentQueryService documentQueryService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private Authentication authentication;
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DocumentController(documentQueryService))
                .setControllerAdvice(new CommonExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();

        principal = new AuthPrincipal(USER_ID, UserRole.WORKER, "김근로");
        authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    @Test
    void returnsApprovedDocumentItemWithoutPersistenceFields() throws Exception {
        when(documentQueryService.findDocuments(
                USER_ID, UserRole.WORKER, 3L, "HEALTH_CERTIFICATE", 0, 20))
                .thenReturn(PageResponse.of(List.of(listItem()), 0, 20, 1));

        MvcResult result = mockMvc.perform(get("/api/documents")
                        .param("workplaceId", "3")
                        .param("docType", "HEALTH_CERTIFICATE")
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].documentId").value(DOCUMENT_ID))
                .andExpect(jsonPath("$.data.content[0].docType").value("HEALTH_CERTIFICATE"))
                .andExpect(jsonPath("$.data.content[0].source").value("SHARED"))
                .andExpect(jsonPath("$.data.content[0].capabilities.canShare").value(false))
                .andExpect(jsonPath("$.data.page.totalElements").value(1))
                .andReturn();

        JsonNode item = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("content").get(0);
        assertEquals(Set.of(
                "documentId", "docType", "status", "fileName", "mimeType",
                "issuedDate", "expiresDate", "latestVersion", "source",
                "sharedByName", "workplaceId", "workplaceName", "workCaseId",
                "capabilities", "createdAt"), fieldNames(item));
        assertFalse(item.has("storageKey"));
        assertFalse(item.has("ownerUserId"));
        assertFalse(item.has("sharedWithUserId"));
        verify(documentQueryService).findDocuments(
                USER_ID, UserRole.WORKER, 3L, "HEALTH_CERTIFICATE", 0, 20);
    }

    @Test
    void rejectsUnapprovedListQueryBeforeTheService() throws Exception {
        mockMvc.perform(get("/api/documents")
                        .param("source", "SHARED")
                        .principal(authentication))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(documentQueryService, never()).findDocuments(
                any(Long.class), any(), any(), any(), any(Integer.class), any(Integer.class));
    }

    @Test
    void noLongerPublishesTheUnapprovedDetailEndpoint() throws Exception {
        mockMvc.perform(get("/api/documents/{documentId}", DOCUMENT_ID)
                        .principal(authentication))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsOwnerOnlyShareHistoryWithoutRecipientOrDocumentIds() throws Exception {
        when(documentQueryService.findShares(USER_ID, DOCUMENT_ID))
                .thenReturn(DocumentShareListResponse.of(List.of(shareItem())));

        MvcResult result = mockMvc.perform(get("/api/documents/{documentId}/shares", DOCUMENT_ID)
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].shareId").value(91))
                .andExpect(jsonPath("$.data.items[0].workplaceId").value(3))
                .andExpect(jsonPath("$.data.items[0].status").value("ACTIVE"))
                .andReturn();

        JsonNode item = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("items").get(0);
        assertEquals(Set.of(
                "shareId", "workplaceId", "workplaceName", "workCaseId",
                "status", "sharedAt", "revokedAt", "effectiveUntil"), fieldNames(item));
        assertFalse(item.has("documentId"));
        assertFalse(item.has("sharedWithUserId"));
    }

    @Test
    void rejectsRequestsWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verify(documentQueryService, never()).findDocuments(
                any(Long.class), any(), any(), any(), any(Integer.class), any(Integer.class));
    }

    private DocumentListItem listItem() {
        return DocumentListItem.of(
                DOCUMENT_ID,
                "HEALTH_CERTIFICATE",
                "ACTIVE",
                "image/jpeg",
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2027, 6, 1),
                1,
                "SHARED",
                "김알바",
                "김알바",
                3L,
                "강남점",
                201L,
                "김알바",
                false,
                LocalDateTime.of(2026, 6, 1, 10, 0));
    }

    private DocumentShareItem shareItem() {
        return DocumentShareItem.of(
                91L,
                3L,
                "강남점",
                201L,
                "ACTIVE",
                LocalDateTime.of(2026, 8, 11, 12, 0),
                null,
                LocalDateTime.of(2026, 8, 20, 18, 0));
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
