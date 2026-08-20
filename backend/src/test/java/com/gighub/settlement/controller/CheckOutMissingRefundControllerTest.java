package com.gighub.settlement.controller;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.CommonExceptionHandler;
import com.gighub.config.ApiJsonMapper;
import com.gighub.member.domain.UserRole;
import com.gighub.settlement.service.SettlementService;
import com.gighub.settlement.service.command.CheckOutMissingRefundApproveCommand;
import com.gighub.settlement.service.result.SettlementResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CheckOutMissingRefundControllerTest {

    private static final long OWNER_ID = 3L;
    private static final long WORK_CASE_ID = 1L;
    private static final long WAGE = 300_000L;
    private static final String PATH =
            "/api/work-cases/{workCaseId}/settlement/check-out-missing-refund/approve";

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
    void exposesTheDedicatedOwnerApprovalContract() throws Exception {
        when(settlementService.approveCheckOutMissingRefund(any()))
                .thenReturn(refunded());

        mockMvc.perform(post(PATH, WORK_CASE_ID)
                        .principal(new UsernamePasswordAuthenticationToken(
                                new AuthPrincipal(OWNER_ID, UserRole.OWNER, "김사장"),
                                "N/A",
                                List.of()))
                        .header("Idempotency-Key", "MISSING-REFUND-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REFUNDED"))
                .andExpect(jsonPath("$.data.workerPaidAmount").value(0))
                .andExpect(jsonPath("$.data.ownerRefundAmount").value(WAGE))
                .andExpect(jsonPath("$.data.calculationReason")
                        .value("CHECK_OUT_MISSING"));

        ArgumentCaptor<CheckOutMissingRefundApproveCommand> command =
                ArgumentCaptor.forClass(CheckOutMissingRefundApproveCommand.class);
        verify(settlementService).approveCheckOutMissingRefund(command.capture());
        assertEquals(WORK_CASE_ID, command.getValue().getWorkCaseId());
        assertEquals(OWNER_ID, command.getValue().getApproverUserId());
        assertEquals("MISSING-REFUND-001", command.getValue().getIdempotencyKey());
    }

    private SettlementResult refunded() {
        return SettlementResult.builder()
                .settlementId(12L)
                .status("REFUNDED")
                .settlementAmount(WAGE)
                .originalEscrowAmount(WAGE)
                .workerPaidAmount(0L)
                .ownerRefundAmount(WAGE)
                .deductionAmount(WAGE)
                .deductionBaseMinutes(480L)
                .lateMinutes(15L)
                .earlyLeaveMinutes(0L)
                .calculationReason("CHECK_OUT_MISSING")
                .calculationVersion("ATTENDANCE_V1")
                .calculatedAt(LocalDateTime.of(2026, 8, 20, 13, 0))
                .completedAt(LocalDateTime.of(2026, 8, 20, 14, 0))
                .build();
    }
}
