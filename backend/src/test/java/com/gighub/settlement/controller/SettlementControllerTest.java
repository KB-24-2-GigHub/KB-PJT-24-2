package com.gighub.settlement.controller;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.CommonExceptionHandler;
import com.gighub.config.ApiJsonMapper;
import com.gighub.member.domain.UserRole;
import com.gighub.settlement.exception.SettlementAlreadyProcessedException;
import com.gighub.settlement.exception.SettlementNotReadyException;
import com.gighub.settlement.exception.SettlementOnHoldException;
import com.gighub.settlement.service.SettlementService;
import com.gighub.settlement.service.command.SettlementApproveCommand;
import com.gighub.settlement.service.result.SettlementResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 정산 승인 Endpoint의 HTTP 계약을 확인합니다.
 *
 * <p>구형 예치 Endpoint가 사라지면서 같은 Controller에 있던 정산 검증을 이 파일로
 * 옮겼습니다. 경로와 응답 계약은 그대로입니다.</p>
 */
class SettlementControllerTest {

    private static final Long EMPLOYER_ID = 3L;
    private static final Long WORK_CASE_ID = 1L;
    private static final Long WAGE = 300_000L;
    private static final String PATH = "/api/work-cases/{workCaseId}/settlement/approve";
    private static final String KEY = "SETTLEMENT-KEY-001";
    private static final LocalDateTime COMPLETED_AT =
            LocalDateTime.of(2026, 7, 24, 17, 12, 34, 123_456_000);

    private SettlementService settlementService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        settlementService = mock(SettlementService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SettlementController(settlementService))
                .setControllerAdvice(new CommonExceptionHandler())
                .setMessageConverters(
                        new MappingJackson2HttpMessageConverter(ApiJsonMapper.create()))
                .build();
    }

    @Test
    void approveSettlementReturnsStoredResultFields() throws Exception {
        when(settlementService.approve(any())).thenReturn(
                SettlementResult.builder()
                        .settlementId(12L)
                        .status("COMPLETED")
                        .settlementAmount(WAGE)
                        .originalEscrowAmount(WAGE)
                        .workerPaidAmount(WAGE)
                        .ownerRefundAmount(0L)
                        .completedAt(COMPLETED_AT)
                        .replayed(false)
                        .build()
        );

        mockMvc.perform(post(PATH, WORK_CASE_ID)
                        .principal(employerAuthentication())
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settlementId").value(12))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.originalEscrowAmount").value(WAGE))
                .andExpect(jsonPath("$.data.workerPaidAmount").value(WAGE))
                .andExpect(jsonPath("$.data.ownerRefundAmount").value(0))
                .andExpect(jsonPath("$.data.completedAt").value("2026-07-24T08:12:34.123456Z"))
                .andExpect(jsonPath("$.data.settlementAmount").doesNotExist())
                .andExpect(jsonPath("$.data.replayed").doesNotExist());

        ArgumentCaptor<SettlementApproveCommand> captor =
                ArgumentCaptor.forClass(SettlementApproveCommand.class);
        verify(settlementService).approve(captor.capture());
        assertEquals(WORK_CASE_ID, captor.getValue().getWorkCaseId());
        assertEquals(EMPLOYER_ID, captor.getValue().getApproverUserId());
        assertEquals(UserRole.OWNER, captor.getValue().getApproverRole());
        assertEquals(KEY, captor.getValue().getIdempotencyKey());
    }

    @Test
    void replayAddsOnlyTheApprovedResponseHeader() throws Exception {
        when(settlementService.approve(any())).thenReturn(
                SettlementResult.builder()
                        .settlementId(12L)
                        .status("COMPLETED")
                        .settlementAmount(WAGE)
                        .originalEscrowAmount(WAGE)
                        .workerPaidAmount(WAGE)
                        .ownerRefundAmount(0L)
                        .completedAt(COMPLETED_AT)
                        .replayed(true)
                        .build());

        mockMvc.perform(post(PATH, WORK_CASE_ID)
                        .principal(employerAuthentication())
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.data.settlementId").value(12))
                .andExpect(jsonPath("$.data.originalEscrowAmount").value(WAGE))
                .andExpect(jsonPath("$.data.workerPaidAmount").value(WAGE))
                .andExpect(jsonPath("$.data.ownerRefundAmount").value(0))
                .andExpect(jsonPath("$.data.completedAt")
                        .value("2026-07-24T08:12:34.123456Z"));
    }

    @Test
    void approveSettlementRejectsANonEmptyBody() throws Exception {
        mockMvc.perform(post(PATH, WORK_CASE_ID)
                        .principal(employerAuthentication())
                        .header("Idempotency-Key", KEY)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(settlementService, never()).approve(any());
    }

    @Test
    void approveSettlementRequiresLoginBeforeServiceCall() throws Exception {
        mockMvc.perform(post(PATH, WORK_CASE_ID).header("Idempotency-Key", KEY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verify(settlementService, never()).approve(any());
    }

    @Test
    void approveSettlementRequiresTheIdempotencyHeader() throws Exception {
        mockMvc.perform(post(PATH, WORK_CASE_ID).principal(employerAuthentication()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(settlementService, never()).approve(any());
    }

    @Test
    void approvedSettlementConflictsExposeTheirExactCodes() throws Exception {
        assertConflict(new SettlementOnHoldException(), "SETTLEMENT_ON_HOLD");
        assertConflict(new SettlementNotReadyException(), "SETTLEMENT_NOT_READY");
        assertConflict(
                new SettlementAlreadyProcessedException(),
                "SETTLEMENT_ALREADY_PROCESSED");
    }

    private void assertConflict(RuntimeException failure, String code) throws Exception {
        reset(settlementService);
        when(settlementService.approve(any())).thenThrow(failure);

        mockMvc.perform(post(PATH, WORK_CASE_ID)
                        .principal(employerAuthentication())
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(code));
    }

    private static Authentication employerAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(EMPLOYER_ID, UserRole.OWNER, "김사장"), "N/A", List.of());
    }
}
