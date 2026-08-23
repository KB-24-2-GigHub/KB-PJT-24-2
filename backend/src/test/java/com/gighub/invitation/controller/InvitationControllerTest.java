package com.gighub.invitation.controller;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.AuthRequiredException;
import com.gighub.common.exception.CommonExceptionHandler;
import com.gighub.common.exception.ConflictException;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.common.trace.TraceIdFilter;
import com.gighub.config.ApiJsonMapper;
import com.gighub.invitation.dto.InvitationDetailResponse;
import com.gighub.invitation.dto.OwnerBadgeResponse;
import com.gighub.invitation.exception.InvitationAlreadyAcceptedException;
import com.gighub.invitation.exception.InvitationExpiredException;
import com.gighub.invitation.exception.InvitationNotFoundException;
import com.gighub.invitation.exception.InvitationRevokedException;
import com.gighub.invitation.exception.InvitationTermsChangedException;
import com.gighub.invitation.service.InvitationAcceptService;
import com.gighub.invitation.service.InvitationQueryService;
import com.gighub.member.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 초대 조회 Endpoint의 HTTP 계약을 확인합니다.
 */
class InvitationControllerTest {

    private static final String TOKEN = "3rXQ0Zk8m1UvJ2Nw6bTyaPcLdEfGhIjKlMnOpQrStUv";

    private InvitationQueryService invitationQueryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        invitationQueryService = mock(InvitationQueryService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new InvitationController(
                        invitationQueryService, mock(InvitationAcceptService.class)))
                .setControllerAdvice(new CommonExceptionHandler())
                // 운영 Converter와 같은 JSON 규칙이어야 Instant·금액 표현을 검증할 수 있습니다.
                .setMessageConverters(
                        new MappingJackson2HttpMessageConverter(ApiJsonMapper.create()))
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    void returnsApprovedTermsEnvelopeForAuthenticatedWorker() throws Exception {
        when(invitationQueryService.findByToken(any(), any())).thenReturn(detail(
                OwnerBadgeResponse.of("TRUST_OWNER", 2)));

        mockMvc.perform(get("/api/invitations/{token}", TOKEN)
                        .principal(workerAuthentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("주말 홀 서빙"))
                .andExpect(jsonPath("$.data.workplaceName").value("강남점"))
                .andExpect(jsonPath("$.data.startsAt").value("2026-08-20T01:00:00Z"))
                .andExpect(jsonPath("$.data.endsAt").value("2026-08-20T09:00:00Z"))
                .andExpect(jsonPath("$.data.breakMinutes").value(60))
                .andExpect(jsonPath("$.data.breakPaid").value(false))
                .andExpect(jsonPath("$.data.dailyWage").value(120000))
                .andExpect(jsonPath("$.data.termsVersion").doesNotExist())
                .andExpect(jsonPath("$.data.expiresAt").value("2026-08-20T01:00:00Z"))
                .andExpect(jsonPath("$.data.ownerBadge.badgeType").value("TRUST_OWNER"))
                .andExpect(jsonPath("$.data.ownerBadge.level").value(2))
                // Token 원문은 요청 경로에만 있고 응답으로 되돌아가지 않아야 합니다.
                .andExpect(jsonPath("$.data.token").doesNotExist());
    }

    @Test
    void ownerBadgeIsNullWhenTheInvitingOwnerHasNoActiveBadge() throws Exception {
        when(invitationQueryService.findByToken(any(), any())).thenReturn(detail(null));

        mockMvc.perform(get("/api/invitations/{token}", TOKEN)
                        .principal(workerAuthentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownerBadge").value(nullValue()))
                .andExpect(jsonPath("$.data.title").value("주말 홀 서빙"));
    }

    @Test
    void passesAuthenticatedPrincipalAndRawPathTokenToService() throws Exception {
        when(invitationQueryService.findByToken(any(), any())).thenReturn(detail(null));

        mockMvc.perform(get("/api/invitations/{token}", TOKEN)
                        .principal(workerAuthentication()))
                .andExpect(status().isOk());

        ArgumentCaptor<AuthPrincipal> principal = ArgumentCaptor.forClass(AuthPrincipal.class);
        verifyServiceCall(principal);
        assertEquals(11L, principal.getValue().getUserId());
        assertEquals(UserRole.WORKER, principal.getValue().getRole());
    }

    @Test
    void unauthenticatedRequestUsesTheCommonAuthContract() throws Exception {
        // 실제 배포에서는 Security 경계가 먼저 끊지만, Principal이 없는 요청이
        // Controller까지 도달해도 같은 401 계약으로 끝나야 합니다.
        mockMvc.perform(get("/api/invitations/{token}", TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void nonWorkerRoleIsDistinguishedFromPlainForbidden() throws Exception {
        when(invitationQueryService.findByToken(any(), any()))
                .thenThrow(new RoleMismatchException("초대는 WORKER만 조회할 수 있습니다."));

        mockMvc.perform(get("/api/invitations/{token}", TOKEN)
                        .principal(ownerAuthentication()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
    }

    @Test
    void invitationStateFailuresKeepTheirApprovedStatusAndCode() throws Exception {
        assertFailure(new InvitationNotFoundException(), 404, "RESOURCE_NOT_FOUND");
        assertFailure(new InvitationExpiredException(), 410, "INVITATION_EXPIRED");
        assertFailure(new InvitationRevokedException(), 409, "INVITATION_REVOKED");
        assertFailure(new InvitationAlreadyAcceptedException(), 409, "INVITATION_ALREADY_ACCEPTED");
        assertFailure(new InvitationTermsChangedException(), 409, "INVITATION_TERMS_CHANGED");
        assertFailure(new ConflictException("초대 상태를 다시 확인해 주세요."), 409, "CONFLICT");
    }

    @Test
    void failureBodiesNeverEchoTheToken() throws Exception {
        List<RuntimeException> failures = List.of(
                new InvitationNotFoundException(),
                new InvitationExpiredException(),
                new InvitationRevokedException(),
                new AuthRequiredException("로그인이 필요합니다.")
        );

        for (RuntimeException failure : failures) {
            // 이미 예외를 던지도록 Stub된 Mock은 when(...) 호출 자체가 그 예외를 냅니다.
            doThrow(failure).when(invitationQueryService).findByToken(any(), any());

            String body = mockMvc.perform(get("/api/invitations/{token}", TOKEN)
                            .principal(workerAuthentication()))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertFalse(
                    body.contains(TOKEN),
                    failure.getClass().getSimpleName() + " 응답에 Token 원문이 남으면 안 됩니다."
            );
        }
    }

    private void assertFailure(RuntimeException failure, int status, String code) throws Exception {
        doThrow(failure).when(invitationQueryService).findByToken(any(), any());

        mockMvc.perform(get("/api/invitations/{token}", TOKEN)
                        .principal(workerAuthentication()))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    private void verifyServiceCall(ArgumentCaptor<AuthPrincipal> principal) {
        verify(invitationQueryService).findByToken(principal.capture(), eq(TOKEN));
    }

    private static InvitationDetailResponse detail(OwnerBadgeResponse ownerBadge) {
        return InvitationDetailResponse.of(
                "주말 홀 서빙",
                "강남점",
                Instant.parse("2026-08-20T01:00:00Z"),
                Instant.parse("2026-08-20T09:00:00Z"),
                60,
                false,
                120_000L,
                Instant.parse("2026-08-20T01:00:00Z"),
                ownerBadge
        );
    }

    private static Authentication workerAuthentication() {
        return authentication(new AuthPrincipal(11L, UserRole.WORKER, "김알바"));
    }

    private static Authentication ownerAuthentication() {
        return authentication(new AuthPrincipal(3L, UserRole.OWNER, "김사장"));
    }

    private static Authentication authentication(AuthPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, "N/A", List.of());
    }
}
