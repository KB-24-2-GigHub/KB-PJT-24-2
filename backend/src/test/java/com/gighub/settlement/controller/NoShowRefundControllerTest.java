package com.gighub.settlement.controller;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.CommonExceptionHandler;
import com.gighub.config.ApiJsonMapper;
import com.gighub.member.domain.UserRole;
import com.gighub.settlement.service.SettlementService;
import com.gighub.settlement.service.command.NoShowRefundApproveCommand;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NoShowRefundControllerTest {

    private static final long OWNER_ID = 3L;
    private static final long WORK_CASE_ID = 1L;
    private static final long WAGE = 300_000L;
    private static final String PATH =
            "/api/work-cases/{workCaseId}/settlement/no-show-refund/approve";
    private static final String KEY = "NO-SHOW-REFUND-KEY-001";

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
    void returnsTheApprovedRefundContractAndReplayHeader() throws Exception {
        when(settlementService.approveNoShowRefund(any())).thenReturn(refunded(true));

        mockMvc.perform(post(PATH, WORK_CASE_ID)
                        .principal(ownerAuthentication())
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.data.settlementId").value(12))
                .andExpect(jsonPath("$.data.status").value("REFUNDED"))
                .andExpect(jsonPath("$.data.originalEscrowAmount").value(WAGE))
                .andExpect(jsonPath("$.data.workerPaidAmount").value(0))
                .andExpect(jsonPath("$.data.ownerRefundAmount").value(WAGE))
                .andExpect(jsonPath("$.data.completedAt")
                        .value("2026-08-13T05:00:00Z"));

        ArgumentCaptor<NoShowRefundApproveCommand> command =
                ArgumentCaptor.forClass(NoShowRefundApproveCommand.class);
        verify(settlementService).approveNoShowRefund(command.capture());
        assertEquals(WORK_CASE_ID, command.getValue().getWorkCaseId());
        assertEquals(OWNER_ID, command.getValue().getApproverUserId());
        assertEquals(UserRole.OWNER, command.getValue().getApproverRole());
        assertEquals(KEY, command.getValue().getIdempotencyKey());
    }

    @Test
    void rejectsAnyRequestBodyBeforeCallingTheService() throws Exception {
        mockMvc.perform(post(PATH, WORK_CASE_ID)
                        .principal(ownerAuthentication())
                        .header("Idempotency-Key", KEY)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(settlementService, never()).approveNoShowRefund(any());
    }

    private SettlementResult refunded(boolean replayed) {
        return SettlementResult.builder()
                .settlementId(12L)
                .status("REFUNDED")
                .settlementAmount(WAGE)
                .originalEscrowAmount(WAGE)
                .workerPaidAmount(0L)
                .ownerRefundAmount(WAGE)
                .completedAt(LocalDateTime.of(2026, 8, 13, 14, 0))
                .replayed(replayed)
                .build();
    }

    private static Authentication ownerAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(OWNER_ID, UserRole.OWNER, "김사장"), "N/A", List.of());
    }
}
