package com.gighub.badge.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.badge.service.BadgeApplicationService;
import com.gighub.badge.service.result.BadgeCalculationResult;
import com.gighub.common.exception.CommonExceptionHandler;
import com.gighub.config.ApiJsonMapper;
import com.gighub.member.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BadgeControllerTest {

    private BadgeApplicationService badgeApplicationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        badgeApplicationService = mock(BadgeApplicationService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new BadgeController(badgeApplicationService))
                .setControllerAdvice(new CommonExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(ApiJsonMapper.create()))
                .build();
    }

    @Test
    void returnsApprovedShapeForAuthenticatedOwner() throws Exception {
        when(badgeApplicationService.recalculate(3L)).thenReturn(BadgeCalculationResult.of(
                "TRUST_OWNER", 1, 12, 10, 10, 80, 8, 90));

        mockMvc.perform(get("/api/users/me/badge").principal(ownerAuthentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.badgeType").value("TRUST_OWNER"))
                .andExpect(jsonPath("$.data.level").value(1))
                .andExpect(jsonPath("$.data.recentCount").value(12))
                .andExpect(jsonPath("$.data.remainingToNextLevel").value(8))
                .andExpect(jsonPath("$.data.criterionLabel").value("안심거래"))
                .andExpect(jsonPath("$.data.criterionDesc").isString());
        verify(badgeApplicationService).recalculate(eq(3L));
    }

    @Test
    void unauthenticatedRequestUsesTheCommonAuthContract() throws Exception {
        mockMvc.perform(get("/api/users/me/badge"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    private static Authentication ownerAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(3L, UserRole.OWNER, "김사장"), "N/A", List.of());
    }
}
