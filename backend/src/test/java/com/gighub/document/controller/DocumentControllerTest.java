package com.gighub.document.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.api.PageResponse;
import com.gighub.common.exception.CommonExceptionHandler;
import com.gighub.common.exception.ConflictException;
import com.gighub.document.dto.DocumentDetailResponse;
import com.gighub.document.dto.DocumentListItem;
import com.gighub.document.dto.DocumentShareItem;
import com.gighub.document.dto.DocumentVersionItem;
import com.gighub.document.exception.ContractRetentionRequiredException;
import com.gighub.document.exception.DocumentNotFoundException;
import com.gighub.document.service.DocumentDeleteService;
import com.gighub.document.service.DocumentQueryService;
import com.gighub.document.service.HealthCertificateRegisterService;
import com.gighub.document.service.HealthCertificateShareService;
import com.gighub.document.service.HealthCertificateUpdateService;
import com.gighub.member.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 문서 목록과 공유 조회가 승인된 공개 응답만 노출하는지 검증합니다. */
@ExtendWith(MockitoExtension.class)
class DocumentControllerTest {

    private static final Long USER_ID = 7L;
    private static final Long DOCUMENT_ID = 11L;

    @Mock
    private DocumentQueryService documentQueryService;

    @Mock
    private HealthCertificateRegisterService healthCertificateRegisterService;

    @Mock
    private HealthCertificateUpdateService healthCertificateUpdateService;

    @Mock
    private DocumentDeleteService documentDeleteService;

    @Mock
    private HealthCertificateShareService healthCertificateShareService;

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
                .standaloneSetup(new DocumentController(
                        documentQueryService,
                        healthCertificateRegisterService,
                        healthCertificateUpdateService,
                        documentDeleteService,
                        healthCertificateShareService))
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
    void returnsApprovedDetailItemAndOnlyTheAllowedVersionFields() throws Exception {
        when(documentQueryService.findDocument(
                USER_ID, UserRole.WORKER, DOCUMENT_ID, 201L))
                .thenReturn(DocumentDetailResponse.of(
                        listItem(),
                        List.of(DocumentVersionItem.of(
                                1,
                                "ORIGINAL",
                                "image/jpeg",
                                128_400L,
                                LocalDateTime.of(2026, 6, 1, 10, 0)))));

        MvcResult result = mockMvc.perform(get("/api/documents/{documentId}", DOCUMENT_ID)
                        .param("workCaseId", "201")
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentId").value(DOCUMENT_ID))
                .andExpect(jsonPath("$.data.versions[0].versionNo").value(1))
                .andExpect(jsonPath("$.data.versions[0].versionType").value("ORIGINAL"))
                .andReturn();

        JsonNode detail = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data");
        assertEquals(Set.of(
                "documentId", "docType", "status", "fileName", "mimeType",
                "issuedDate", "expiresDate", "latestVersion", "source",
                "sharedByName", "workplaceId", "workplaceName", "workCaseId",
                "capabilities", "createdAt", "versions"), fieldNames(detail));
        assertFalse(detail.has("item"));
        assertFalse(detail.has("storageKey"));
        assertFalse(detail.has("ownerUserId"));
        assertEquals(Set.of(
                "versionNo", "versionType", "mimeType", "sizeBytes", "createdAt"),
                fieldNames(detail.path("versions").get(0)));
        assertFalse(detail.path("versions").get(0).has("checksum"));
        assertFalse(detail.path("versions").get(0).has("id"));

        verify(documentQueryService).findDocument(
                USER_ID, UserRole.WORKER, DOCUMENT_ID, 201L);
    }

    @Test
    void returnsOwnerOnlyShareHistoryWithoutRecipientOrDocumentIds() throws Exception {
        when(documentQueryService.findShares(USER_ID, DOCUMENT_ID, 0, 20))
                .thenReturn(PageResponse.of(List.of(shareItem()), 0, 20, 1));

        MvcResult result = mockMvc.perform(get("/api/documents/{documentId}/shares", DOCUMENT_ID)
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].shareId").value(91))
                .andExpect(jsonPath("$.data.content[0].workplaceId").value(3))
                .andExpect(jsonPath("$.data.content[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.page.number").value(0))
                .andExpect(jsonPath("$.data.page.size").value(20))
                .andExpect(jsonPath("$.data.page.totalElements").value(1))
                .andExpect(jsonPath("$.data.page.totalPages").value(1))
                .andReturn();

        JsonNode item = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("content").get(0);
        assertEquals(Set.of(
                "shareId", "workplaceId", "workplaceName", "workCaseId",
                "status", "sharedAt", "revokedAt", "effectiveUntil"), fieldNames(item));
        assertFalse(item.has("documentId"));
        assertFalse(item.has("sharedWithUserId"));
        verify(documentQueryService).findShares(USER_ID, DOCUMENT_ID, 0, 20);
    }

    @Test
    void forwardsRequestedShareHistoryPage() throws Exception {
        when(documentQueryService.findShares(USER_ID, DOCUMENT_ID, 2, 10))
                .thenReturn(PageResponse.of(List.of(), 2, 10, 21));

        mockMvc.perform(get("/api/documents/{documentId}/shares", DOCUMENT_ID)
                        .param("page", "2")
                        .param("size", "10")
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.page.number").value(2))
                .andExpect(jsonPath("$.data.page.size").value(10))
                .andExpect(jsonPath("$.data.page.totalElements").value(21))
                .andExpect(jsonPath("$.data.page.totalPages").value(3));

        verify(documentQueryService).findShares(USER_ID, DOCUMENT_ID, 2, 10);
    }

    @Test
    void rejectsRequestsWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(get("/api/documents/{documentId}", DOCUMENT_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verify(documentQueryService, never()).findDocuments(
                any(Long.class), any(), any(), any(), any(Integer.class), any(Integer.class));
        verify(documentQueryService, never()).findDocument(
                anyLong(), any(), anyLong(), any());
    }

    @Test
    void registersAHealthCertificateAndReturnsTheCreatedItem() throws Exception {
        DocumentListItem registered = DocumentListItem.of(
                DOCUMENT_ID,
                "HEALTH_CERTIFICATE",
                "ACTIVE",
                "image/jpeg",
                LocalDate.of(2026, 8, 14),
                LocalDate.of(2027, 8, 14),
                1,
                "OWN",
                "김근로",
                null,
                null,
                null,
                null,
                null,
                false,
                LocalDateTime.of(2026, 8, 14, 12, 0));
        when(healthCertificateRegisterService.register(
                eq(principal), eq("HEALTH_CERTIFICATE"), eq(LocalDate.of(2026, 8, 14)), any()))
                .thenReturn(registered);

        mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile(
                                "file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3}))
                        .param("docType", "HEALTH_CERTIFICATE")
                        .param("issuedDate", "2026-08-14")
                        .principal(authentication))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.documentId").value(DOCUMENT_ID))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.capabilities.canShare").value(false));

        verify(healthCertificateRegisterService).register(
                eq(principal), eq("HEALTH_CERTIFICATE"), eq(LocalDate.of(2026, 8, 14)), any());
    }

    @Test
    void updatesAHealthCertificateIssuedDateAndReturnsTheUpdatedItem() throws Exception {
        DocumentListItem updated = DocumentListItem.of(
                DOCUMENT_ID,
                "HEALTH_CERTIFICATE",
                "ACTIVE",
                "image/jpeg",
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 9, 1),
                1,
                "OWN",
                "김근로",
                null,
                null,
                null,
                null,
                null,
                false,
                LocalDateTime.of(2026, 8, 14, 12, 0));
        when(healthCertificateUpdateService.updateIssuedDate(
                principal, DOCUMENT_ID, LocalDate.of(2026, 9, 1)))
                .thenReturn(updated);

        mockMvc.perform(patch("/api/documents/{documentId}", DOCUMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issuedDate\":\"2026-09-01\"}")
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.issuedDate").value("2026-09-01"))
                .andExpect(jsonPath("$.data.expiresDate").value("2027-09-01"));

        verify(healthCertificateUpdateService).updateIssuedDate(
                principal, DOCUMENT_ID, LocalDate.of(2026, 9, 1));
    }

    @Test
    void rejectsAHealthCertificateUpdateWithoutAnIssuedDate() throws Exception {
        mockMvc.perform(patch("/api/documents/{documentId}", DOCUMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .principal(authentication))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(healthCertificateUpdateService, never()).updateIssuedDate(any(), anyLong(), any());
    }

    @Test
    void deletesAHealthCertificateAndReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/documents/{documentId}", DOCUMENT_ID)
                        .principal(authentication))
                .andExpect(status().isNoContent());

        verify(documentDeleteService).delete(principal, DOCUMENT_ID);
    }

    @Test
    void rejectsDeletingAnEmploymentContractWithConflict() throws Exception {
        doThrow(new ContractRetentionRequiredException("근로계약서는 삭제할 수 없습니다."))
                .when(documentDeleteService).delete(principal, DOCUMENT_ID);

        mockMvc.perform(delete("/api/documents/{documentId}", DOCUMENT_ID)
                        .principal(authentication))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTRACT_RETENTION_REQUIRED"));
    }

    @Test
    void returnsNotFoundWhenDeletingAMissingOrUnownedDocument() throws Exception {
        doThrow(new DocumentNotFoundException("문서를 찾을 수 없습니다."))
                .when(documentDeleteService).delete(principal, DOCUMENT_ID);

        mockMvc.perform(delete("/api/documents/{documentId}", DOCUMENT_ID)
                        .principal(authentication))
                .andExpect(status().isNotFound());
    }

    @Test
    void createsAHealthCertificateShareAndReturnsOnlyTheShareId() throws Exception {
        when(healthCertificateShareService.share(principal, DOCUMENT_ID, 3L)).thenReturn(99L);

        MvcResult result = mockMvc.perform(post("/api/documents/{documentId}/shares", DOCUMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workplaceId\":3}")
                        .principal(authentication))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.shareId").value(99))
                .andReturn();

        // 서버가 파생한 관계는 응답으로 내보내지 않는다.
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertEquals(Set.of("shareId"), fieldNames(data));
    }

    @Test
    void rejectsAShareRequestWithoutAWorkplaceId() throws Exception {
        mockMvc.perform(post("/api/documents/{documentId}/shares", DOCUMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .principal(authentication))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(healthCertificateShareService, never()).share(any(), anyLong(), any());
    }

    /** workCaseId는 서버가 파생하므로 요청으로 받으면 조용히 무시하지 않고 거부한다. */
    @Test
    void rejectsAShareRequestThatTriesToChooseTheWorkCase() throws Exception {
        mockMvc.perform(post("/api/documents/{documentId}/shares", DOCUMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workplaceId\":3,\"workCaseId\":21}")
                        .principal(authentication))
                .andExpect(status().isBadRequest());

        verify(healthCertificateShareService, never()).share(any(), anyLong(), any());
    }

    @Test
    void returnsConflictWhenTheSameWorkplaceIsAlreadyShared() throws Exception {
        when(healthCertificateShareService.share(principal, DOCUMENT_ID, 3L))
                .thenThrow(new ConflictException("이미 이 사업장에 공유 중인 보건증입니다."));

        mockMvc.perform(post("/api/documents/{documentId}/shares", DOCUMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workplaceId\":3}")
                        .principal(authentication))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void returnsNotFoundWhenSharingAMissingOrUnownedDocument() throws Exception {
        when(healthCertificateShareService.share(principal, DOCUMENT_ID, 3L))
                .thenThrow(new DocumentNotFoundException("문서를 찾을 수 없습니다."));

        mockMvc.perform(post("/api/documents/{documentId}/shares", DOCUMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workplaceId\":3}")
                        .principal(authentication))
                .andExpect(status().isNotFound());
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
