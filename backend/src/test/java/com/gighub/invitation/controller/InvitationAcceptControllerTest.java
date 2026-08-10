package com.gighub.invitation.controller;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.CommonExceptionHandler;
import com.gighub.common.exception.ConflictException;
import com.gighub.common.exception.ForbiddenException;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.common.exception.WorkCaseLockedException;
import com.gighub.common.trace.TraceIdFilter;
import com.gighub.config.ApiJsonMapper;
import com.gighub.idempotency.exception.IdempotencyClaimKeyReusedException;
import com.gighub.invitation.application.InvitationAcceptanceResult;
import com.gighub.invitation.exception.InvitationAlreadyAcceptedException;
import com.gighub.invitation.exception.InvitationExpiredException;
import com.gighub.invitation.exception.InvitationNotFoundException;
import com.gighub.invitation.exception.InvitationRevokedException;
import com.gighub.invitation.exception.InvitationTermsChangedException;
import com.gighub.invitation.service.InvitationAcceptResult;
import com.gighub.invitation.service.InvitationAcceptService;
import com.gighub.invitation.service.InvitationQueryService;
import com.gighub.member.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 초대 수락 Endpoint의 HTTP 계약을 확인합니다.
 */
class InvitationAcceptControllerTest {

    private static final String TOKEN = "3rXQ0Zk8m1UvJ2Nw6bTyaPcLdEfGhIjKlMnOpQrStUv";
    private static final String PATH = "/api/invitations/{token}/accept";
    private static final String KEY = "accept-key-0001";

    private InvitationAcceptService invitationAcceptService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        invitationAcceptService = mock(InvitationAcceptService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new InvitationController(
                        mock(InvitationQueryService.class), invitationAcceptService))
                .setControllerAdvice(new CommonExceptionHandler())
                .setMessageConverters(
                        new MappingJackson2HttpMessageConverter(ApiJsonMapper.create()))
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    void firstSuccessReturnsHeldWithoutTheReplayHeader() throws Exception {
        when(invitationAcceptService.accept(any(), eq(TOKEN), eq(KEY)))
                .thenReturn(InvitationAcceptResult.first(InvitationAcceptanceResult.held(123L)));

        mockMvc.perform(post(PATH, TOKEN)
                        .principal(workerAuthentication())
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotency-Replayed"))
                .andExpect(jsonPath("$.data.workCaseId").value(123))
                .andExpect(jsonPath("$.data.escrowStatus").value("HELD"))
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.contractId").doesNotExist());
    }

    @Test
    void replayReturnsTheSameBodyWithTheReplayHeader() throws Exception {
        when(invitationAcceptService.accept(any(), eq(TOKEN), eq(KEY)))
                .thenReturn(InvitationAcceptResult.replayed(InvitationAcceptanceResult.held(123L)));

        mockMvc.perform(post(PATH, TOKEN)
                        .principal(workerAuthentication())
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.data.workCaseId").value(123))
                .andExpect(jsonPath("$.data.escrowStatus").value("HELD"));
    }

    @Test
    void firstAndReplayResponsesAreByteIdenticalExceptForTheReplayHeader() throws Exception {
        when(invitationAcceptService.accept(any(), eq(TOKEN), eq(KEY)))
                .thenReturn(InvitationAcceptResult.first(
                        InvitationAcceptanceResult.held(123L)));
        MvcResult first = mockMvc.perform(post(PATH, TOKEN)
                        .principal(workerAuthentication())
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotency-Replayed"))
                .andReturn();

        when(invitationAcceptService.accept(any(), eq(TOKEN), eq(KEY)))
                .thenReturn(InvitationAcceptResult.replayed(
                        InvitationAcceptanceResult.held(123L)));
        MvcResult replay = mockMvc.perform(post(PATH, TOKEN)
                        .principal(workerAuthentication())
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn();

        org.junit.jupiter.api.Assertions.assertEquals(
                first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
    }

    @Test
    void anyRequestBodyIsRejectedBeforeTheServiceRuns() throws Exception {
        String[] bodies = {
            "{}",
            "null",
            " ",
            "{\"workerId\":9,\"amount\":1}",
            "{\"signatureImage\":\"data:image/png;base64,AAAA\"}"
        };

        for (String body : bodies) {
            mockMvc.perform(post(PATH, TOKEN)
                            .principal(workerAuthentication())
                            .header("Idempotency-Key", KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.message").value("이 요청은 본문을 받지 않습니다."));
        }

        mockMvc.perform(post(PATH, TOKEN)
                        .principal(workerAuthentication())
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .content("--boundary\r\nContent-Disposition: form-data\r\n\r\nx\r\n"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // client가 보낸 값이 조용히 무시되지 않았음을 확인합니다.
        verify(invitationAcceptService, never()).accept(any(), any(), any());
    }

    @Test
    void missingIdempotencyKeyIsAValidationError() throws Exception {
        mockMvc.perform(post(PATH, TOKEN).principal(workerAuthentication()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(invitationAcceptService, never()).accept(any(), any(), any());
    }

    @Test
    void unauthenticatedRequestUsesTheCommonAuthContract() throws Exception {
        mockMvc.perform(post(PATH, TOKEN).header("Idempotency-Key", KEY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void acceptFailuresKeepTheirApprovedStatusAndCode() throws Exception {
        assertFailure(new RoleMismatchException("초대는 WORKER만 수락할 수 있습니다."),
                403, "ROLE_MISMATCH");
        assertFailure(new ForbiddenException("본인이 등록한 근무는 수락할 수 없습니다."),
                403, "FORBIDDEN");
        assertFailure(new InvitationNotFoundException(), 404, "RESOURCE_NOT_FOUND");
        assertFailure(new InvitationExpiredException(), 410, "INVITATION_EXPIRED");
        assertFailure(new InvitationRevokedException(), 409, "INVITATION_REVOKED");
        assertFailure(new InvitationAlreadyAcceptedException(),
                409, "INVITATION_ALREADY_ACCEPTED");
        assertFailure(new InvitationTermsChangedException(), 409, "INVITATION_TERMS_CHANGED");
        assertFailure(new WorkCaseLockedException("확정할 수 없는 근무입니다."),
                409, "WORK_CASE_LOCKED");
        assertFailure(new IdempotencyClaimKeyReusedException(), 409, "IDEMPOTENCY_KEY_REUSED");
        assertFailure(new ConflictException("같은 요청을 처리하고 있습니다."), 409, "CONFLICT");
        assertFailure(
                new ConflictException("사장님의 예치 가능 잔액이 부족하여 근무를 확정할 수 없습니다."),
                409,
                "CONFLICT");
    }

    @Test
    void failureBodiesNeverEchoTheTokenOrTheKey() throws Exception {
        doThrow(new InvitationNotFoundException())
                .when(invitationAcceptService).accept(any(), eq(TOKEN), eq(KEY));

        String body = mockMvc.perform(post(PATH, TOKEN)
                        .principal(workerAuthentication())
                        .header("Idempotency-Key", KEY))
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.junit.jupiter.api.Assertions.assertFalse(body.contains(TOKEN));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains(KEY));
    }

    private void assertFailure(RuntimeException failure, int status, String code) throws Exception {
        doThrow(failure).when(invitationAcceptService).accept(any(), eq(TOKEN), eq(KEY));

        mockMvc.perform(post(PATH, TOKEN)
                        .principal(workerAuthentication())
                        .header("Idempotency-Key", KEY))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private static Authentication workerAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(11L, UserRole.WORKER, "김알바"), "N/A", List.of());
    }
}
