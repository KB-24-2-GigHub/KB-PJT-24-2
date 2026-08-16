package com.gighub.settlement.controller;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.api.PageResponse;
import com.gighub.common.exception.CommonExceptionHandler;
import com.gighub.config.ApiJsonMapper;
import com.gighub.member.domain.UserRole;
import com.gighub.settlement.domain.DisputeStatus;
import com.gighub.settlement.dto.DisputeDemoReviewResponse;
import com.gighub.settlement.dto.DisputeListItemResponse;
import com.gighub.settlement.exception.DisputeAlreadyOpenException;
import com.gighub.settlement.service.DisputeService;
import com.gighub.settlement.service.command.DisputeCreateCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DisputeControllerTest {

    private static final long WORK_CASE_ID = 11L;
    private static final long WORKER_ID = 22L;
    private static final String PATH = "/api/work-cases/{workCaseId}/disputes";

    private DisputeService disputeService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        disputeService = mock(DisputeService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DisputeController(disputeService))
                .setControllerAdvice(new CommonExceptionHandler())
                .setMessageConverters(
                        new MappingJackson2HttpMessageConverter(ApiJsonMapper.create()))
                .build();
    }

    @Test
    void workerCreatesDisputeWithApprovedResponseShape() throws Exception {
        when(disputeService.create(any())).thenReturn(91L);

        mockMvc.perform(post(PATH, WORK_CASE_ID)
                        .principal(workerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content("{\"title\":\"임금 확인\",\"content\":\"약정 일급 미지급\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reportId").value(91));

        ArgumentCaptor<DisputeCreateCommand> command =
                ArgumentCaptor.forClass(DisputeCreateCommand.class);
        verify(disputeService).create(command.capture());
        assertEquals(WORKER_ID, command.getValue().getRequesterUserId());
        assertEquals(UserRole.WORKER, command.getValue().getRequesterRole());
    }

    @Test
    void missingContentIsValidationErrorBeforeServiceCall() throws Exception {
        mockMvc.perform(post(PATH, WORK_CASE_ID)
                        .principal(workerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content("{\"title\":\"임금 확인\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(disputeService, never()).create(any());
    }

    @Test
    void duplicateOpenDisputeUsesDedicatedConflictCode() throws Exception {
        when(disputeService.create(any())).thenThrow(new DisputeAlreadyOpenException());

        mockMvc.perform(post(PATH, WORK_CASE_ID)
                        .principal(workerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content("{\"title\":\"임금 확인\",\"content\":\"약정 일급 미지급\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPUTE_ALREADY_OPEN"));
    }

    @Test
    void partyReadsApprovedPageFieldsWithoutInternalIds() throws Exception {
        DisputeListItemResponse item = new DisputeListItemResponse(
                91L,
                "임금 확인",
                "약정 일급 미지급",
                DisputeStatus.OPEN,
                null,
                UserRole.WORKER,
                Instant.parse("2026-08-15T01:00:00Z"),
                Instant.parse("2026-08-15T01:01:00Z"),
                new DisputeDemoReviewResponse(
                        "SIMULATED_LLM",
                        "COMPLETED",
                        "RELEASE_TO_WORKER",
                        List.of("RELEASE_TO_WORKER"),
                        "근로자 지급 흐름을 재개합니다.",
                        new BigDecimal("0.990"),
                        Instant.parse("2026-08-15T01:01:00Z")));
        when(disputeService.findPage(WORK_CASE_ID, WORKER_ID, UserRole.WORKER, 0, 20))
                .thenReturn(PageResponse.of(List.of(item), 0, 20, 1));

        mockMvc.perform(get(PATH, WORK_CASE_ID).principal(workerAuthentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reportId").value(91))
                .andExpect(jsonPath("$.data.content[0].requesterRole").value("WORKER"))
                .andExpect(jsonPath("$.data.content[0].createdAt")
                        .value("2026-08-15T01:00:00Z"))
                .andExpect(jsonPath("$.data.content[0].demoReview.source")
                        .value("SIMULATED_LLM"))
                .andExpect(jsonPath("$.data.content[0].demoReview.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.content[0].demoReview.decision")
                        .value("RELEASE_TO_WORKER"))
                .andExpect(jsonPath("$.data.content[0].demoReview.reasonCodes[0]")
                        .value("RELEASE_TO_WORKER"))
                .andExpect(jsonPath("$.data.content[0].requesterId").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].resolvedByUserId").doesNotExist())
                .andExpect(jsonPath("$.data.page.totalElements").value(1));
    }

    @Test
    void invalidPageIsRejectedBeforeServiceCall() throws Exception {
        mockMvc.perform(get(PATH, WORK_CASE_ID)
                        .principal(workerAuthentication())
                        .queryParam("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(disputeService, never()).findPage(
                anyLong(), anyLong(), any(), any(Integer.class), any(Integer.class));
    }

    private static Authentication workerAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(WORKER_ID, UserRole.WORKER, "김근로"),
                "N/A",
                List.of());
    }
}
