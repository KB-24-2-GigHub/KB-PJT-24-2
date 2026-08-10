package com.gighub.document.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.CommonExceptionHandler;
import com.gighub.document.dto.DocumentListItem;
import com.gighub.document.mapper.DocumentQueryMapper;
import com.gighub.document.service.DocumentQueryServiceImpl;
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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link DocumentController}의 목록 응답 구조와 Page 경계를 검증합니다.
 *
 * <p>공통 Envelope 계약 테스트는 테스트용 Controller만 확인하므로, 실제 동작이 바뀐
 * {@code GET /api/documents}에서도 승인 구조와 경계가 유지되는지 여기서 고정합니다.</p>
 */
@ExtendWith(MockitoExtension.class)
class DocumentControllerTest {

    private static final Long USER_ID = 7L;

    @Mock
    private DocumentQueryMapper documentQueryMapper;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DocumentController(
                        new DocumentQueryServiceImpl(documentQueryMapper)))
                .setControllerAdvice(new CommonExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();

        AuthPrincipal principal = new AuthPrincipal(USER_ID, UserRole.WORKER, "김근로");
        authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    private DocumentListItem item(long documentId) {
        return DocumentListItem.builder()
                .documentId(documentId)
                .type("CONTRACT")
                .status("ACTIVE")
                .ownerName("홍길동")
                .source("UPLOAD")
                .build();
    }

    // ===================== 응답 구조 =====================

    /**
     * 목록 응답이 {@code data.content}와 승인된 Page Metadata로 직렬화되는지 검증합니다.
     *
     * @throws Exception MockMvc 요청 실행에 실패한 경우
     */
    @Test
    void returnsDocumentPageInApprovedEnvelope() throws Exception {
        when(documentQueryMapper.findDocuments(eq(USER_ID), isNull(), eq(0L), eq(20)))
                .thenReturn(List.of(item(11L), item(12L)));
        when(documentQueryMapper.countDocuments(USER_ID, null)).thenReturn(41);

        MvcResult result = mockMvc.perform(get("/api/documents").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].documentId").value(11))
                .andExpect(jsonPath("$.data.content[0].type").value("CONTRACT"))
                .andExpect(jsonPath("$.data.page.number").value(0))
                .andExpect(jsonPath("$.data.page.size").value(20))
                .andExpect(jsonPath("$.data.page.totalElements").value(41))
                .andExpect(jsonPath("$.data.page.totalPages").value(3))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(Set.of("content", "page"), fieldNames(body.path("data")));
        assertEquals(
                Set.of("number", "size", "totalElements", "totalPages"),
                fieldNames(body.path("data").path("page")));
    }

    /**
     * 결과가 없어도 목록 구조를 유지하고 전체 Page 수를 0으로 보고하는지 검증합니다.
     *
     * @throws Exception MockMvc 요청 실행에 실패한 경우
     */
    @Test
    void keepsStructureWhenNoDocumentsMatch() throws Exception {
        when(documentQueryMapper.findDocuments(any(), any(), anyLong(), anyInt()))
                .thenReturn(List.of());
        when(documentQueryMapper.countDocuments(any(), any())).thenReturn(0);

        mockMvc.perform(get("/api/documents").param("documentType", "CONTRACT").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.page.totalElements").value(0))
                .andExpect(jsonPath("$.data.page.totalPages").value(0));

        verify(documentQueryMapper).countDocuments(USER_ID, "CONTRACT");
    }

    // ===================== Page 경계 =====================

    /**
     * page와 size로 계산한 offset이 조회 조건에 반영되는지 검증합니다.
     *
     * @throws Exception MockMvc 요청 실행에 실패한 경우
     */
    @Test
    void appliesOffsetFromPageAndSize() throws Exception {
        when(documentQueryMapper.findDocuments(eq(USER_ID), isNull(), eq(20L), eq(10)))
                .thenReturn(List.of());
        when(documentQueryMapper.countDocuments(USER_ID, null)).thenReturn(0);

        mockMvc.perform(get("/api/documents")
                        .param("page", "2")
                        .param("size", "10")
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.number").value(2))
                .andExpect(jsonPath("$.data.page.size").value(10));

        verify(documentQueryMapper).findDocuments(USER_ID, null, 20L, 10);
    }

    /**
     * 승인 경계 안의 Page Query가 그대로 통과하는지 검증합니다.
     *
     * @throws Exception MockMvc 요청 실행에 실패한 경우
     */
    @Test
    void acceptsApprovedPageBounds() throws Exception {
        when(documentQueryMapper.findDocuments(any(), any(), anyLong(), anyInt()))
                .thenReturn(List.of());
        when(documentQueryMapper.countDocuments(any(), any())).thenReturn(0);

        mockMvc.perform(get("/api/documents").param("size", "100").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.size").value(100));
    }

    /**
     * 상한을 넘는 size가 조회 전에 차단되는지 검증합니다.
     *
     * @throws Exception MockMvc 요청 실행에 실패한 경우
     */
    @Test
    void rejectsPageSizeAboveLimit() throws Exception {
        mockMvc.perform(get("/api/documents").param("size", "101").principal(authentication))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.traceId").isString());

        verify(documentQueryMapper, never())
                .findDocuments(any(), any(), anyLong(), anyInt());
    }

    /**
     * size 0을 빈 Page로 응답하지 않고 조회 전에 차단하는지 검증합니다.
     *
     * @throws Exception MockMvc 요청 실행에 실패한 경우
     */
    @Test
    void rejectsPageSizeBelowOne() throws Exception {
        mockMvc.perform(get("/api/documents").param("size", "0").principal(authentication))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(documentQueryMapper, never())
                .findDocuments(any(), any(), anyLong(), anyInt());
    }

    /**
     * 음수 Page 번호가 조회 전에 차단되는지 검증합니다.
     *
     * @throws Exception MockMvc 요청 실행에 실패한 경우
     */
    @Test
    void rejectsNegativePage() throws Exception {
        mockMvc.perform(get("/api/documents").param("page", "-1").principal(authentication))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(documentQueryMapper, never())
                .findDocuments(any(), any(), anyLong(), anyInt());
    }

    // ===================== 인증 =====================

    /**
     * 비로그인 요청이 문서를 조회하지 않고 401을 반환하는지 검증합니다.
     *
     * @throws Exception MockMvc 요청 실행에 실패한 경우
     */
    @Test
    void rejectsDocumentsWithoutSession() throws Exception {
        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verify(documentQueryMapper, never())
                .findDocuments(any(), any(), anyLong(), anyInt());
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
