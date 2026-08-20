package com.gighub.auth.security;

import java.util.Map;

import javax.servlet.Filter;
import javax.servlet.http.Cookie;

import com.gighub.auth.controller.AuthController;
import com.gighub.auth.service.AuthService;
import com.gighub.auth.service.LoginResult;
import com.gighub.common.exception.AuthRequiredException;
import com.gighub.common.exception.CommonExceptionHandler;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.member.controller.UserController;
import com.gighub.member.domain.UserRole;
import com.gighub.member.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFlowSecurityIntegrationTest {

    private AnnotationConfigWebApplicationContext rootContext;
    private AnnotationConfigWebApplicationContext servletContext;
    private AuthService authService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MockServletContext mockServletContext = new MockServletContext();

        rootContext = new AnnotationConfigWebApplicationContext();
        rootContext.setServletContext(mockServletContext);
        rootContext.register(RootTestConfig.class);
        rootContext.refresh();

        servletContext = new AnnotationConfigWebApplicationContext();
        servletContext.setServletContext(mockServletContext);
        servletContext.setParent(rootContext);
        servletContext.register(ServletTestConfig.class);
        servletContext.refresh();

        authService = rootContext.getBean(AuthService.class);
        Filter securityFilter = rootContext.getBean("springSecurityFilterChain", Filter.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(servletContext)
                .addFilters(securityFilter)
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        servletContext.close();
        rootContext.close();
    }

    @Test
    void csrfLoginSessionAndLogoutUseOneRealSecurityFilterChain() throws Exception {
        AuthPrincipal principal = new AuthPrincipal(81L, UserRole.OWNER, "김사장");
        when(authService.signup(any())).thenReturn(80L);
        when(authService.login(any())).thenReturn(new LoginResult(principal, true));
        when(authService.needsWorkplaceSetup(principal)).thenReturn(true);

        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andReturn();
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");

        MvcResult signupResult = mockMvc.perform(post("/api/auth/signup")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(APPLICATION_JSON)
                        .content(signupBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.userId").value(80))
                .andReturn();
        assertNull(signupResult.getRequest().getSession(false));

        MockHttpSession anonymousSession = new MockHttpSession();
        String anonymousSessionId = anonymousSession.getId();

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .session(anonymousSession)
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(APPLICATION_JSON)
                        .content(loginBody("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("OWNER"))
                .andExpect(jsonPath("$.data.name").value("김사장"))
                .andExpect(jsonPath("$.data.needsWorkplaceSetup").value(true))
                .andExpect(cookie().maxAge("XSRF-TOKEN", 0))
                .andReturn();
        MockHttpSession authenticatedSession =
                (MockHttpSession) loginResult.getRequest().getSession(false);
        assertNotEquals(anonymousSessionId, authenticatedSession.getId());

        mockMvc.perform(get("/api/auth/session").session(authenticatedSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authenticated").value(true))
                .andExpect(jsonPath("$.data.role").value("OWNER"));

        MvcResult refreshedCsrf = mockMvc.perform(
                        get("/api/auth/csrf").session(authenticatedSession))
                .andExpect(status().isNoContent())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andReturn();
        Cookie refreshedCsrfCookie = refreshedCsrf.getResponse().getCookie("XSRF-TOKEN");

        mockMvc.perform(post("/api/auth/logout")
                        .session(authenticatedSession)
                        .cookie(refreshedCsrfCookie)
                        .header("X-XSRF-TOKEN", refreshedCsrfCookie.getValue()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("JSESSIONID", 0))
                .andExpect(cookie().maxAge("XSRF-TOKEN", 0));
        assertTrue(authenticatedSession.isInvalid());

        mockMvc.perform(get("/api/auth/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authenticated").value(false));
    }

    @Test
    void loginFailuresKeepApprovedErrorCodesAndDoNotCreateSession() throws Exception {
        Cookie csrfCookie = prepareCsrfCookie();
        when(authService.login(any()))
                .thenThrow(new AuthRequiredException("아이디 또는 비밀번호를 확인해 주세요."));

        MvcResult authFailure = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(APPLICATION_JSON)
                        .content(loginBody("OWNER")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andReturn();
        assertNull(authFailure.getRequest().getSession(false));
    }

    @Test
    void validCredentialsWithDifferentRoleUseRoleMismatchCode() throws Exception {
        Cookie csrfCookie = prepareCsrfCookie();
        when(authService.login(any()))
                .thenThrow(new RoleMismatchException("선택한 역할과 계정 역할이 일치하지 않습니다."));

        mockMvc.perform(post("/api/auth/login")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(APPLICATION_JSON)
                        .content(loginBody("WORKER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
    }

    /**
     * 보호 API가 Session 없이는 승인 Envelope의 401로 거절되는지 실제 Filter Chain으로 검증합니다.
     *
     * @throws Exception MockMvc 요청 실행에 실패한 경우
     */
    @Test
    void protectedApiWithoutSessionUsesApprovedUnauthorizedEnvelope() throws Exception {
        mockMvc.perform(get("/api/test-protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    /**
     * 위조한 legacy {@code LOGIN_USER} Attribute만으로는 보호 API에 접근할 수 없음을 검증합니다.
     *
     * @throws Exception MockMvc 요청 실행에 실패한 경우
     */
    @Test
    void forgedLegacyLoginUserAttributeGrantsNoAccess() throws Exception {
        MockHttpSession forgedSession = new MockHttpSession();
        forgedSession.setAttribute("LOGIN_USER", 999L);

        mockMvc.perform(get("/api/test-protected").session(forgedSession))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    /**
     * 인증 Session에 불일치하는 legacy Attribute가 섞여도 접근 주체가 바뀌지 않는지 검증합니다.
     *
     * @throws Exception MockMvc 요청 실행에 실패한 경우
     */
    @Test
    void mismatchedLegacyLoginUserAttributeDoesNotChangeSubject() throws Exception {
        MockHttpSession authenticatedSession = loginSession(81L);
        // 정상 로그인 뒤 다른 사용자 ID를 legacy 키로 덮어써도 Principal이 우선해야 합니다.
        authenticatedSession.setAttribute("LOGIN_USER", 999L);

        mockMvc.perform(get("/api/test-protected").session(authenticatedSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(81));
    }

    /**
     * 로그인 성공 Session이 legacy Attribute를 남기지 않는지 검증합니다.
     *
     * @throws Exception MockMvc 요청 실행에 실패한 경우
     */
    @Test
    void loginDoesNotWriteLegacyLoginUserAttribute() throws Exception {
        assertNull(loginSession(81L).getAttribute("LOGIN_USER"));
    }

    /**
     * 비밀번호 변경이 CSRF Token 없이는 승인 Envelope의 403으로 거절되는지 검증합니다.
     *
     * @throws Exception MockMvc 요청 실행에 실패한 경우
     */
    @Test
    void passwordChangeWithoutCsrfTokenIsRejected() throws Exception {
        MockHttpSession authenticatedSession = loginSession(81L);

        mockMvc.perform(patch("/api/users/me/password")
                        .session(authenticatedSession)
                        .contentType(APPLICATION_JSON)
                        .content(passwordChangeBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /**
     * 비밀번호 변경이 Session 없이는 승인 Envelope의 401로 거절되는지 검증합니다.
     *
     * @throws Exception MockMvc 요청 실행에 실패한 경우
     */
    @Test
    void passwordChangeWithoutSessionIsRejected() throws Exception {
        Cookie csrfCookie = prepareCsrfCookie();

        mockMvc.perform(patch("/api/users/me/password")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(APPLICATION_JSON)
                        .content(passwordChangeBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    /**
     * 비밀번호 변경 성공이 실제 Filter Chain에서 인증을 유지한 채 Session ID만 바꾸는지 검증합니다.
     *
     * @throws Exception MockMvc 요청 실행에 실패한 경우
     */
    @Test
    void passwordChangeRotatesSessionIdAndKeepsAuthentication() throws Exception {
        MockHttpSession authenticatedSession = loginSession(81L);
        String sessionIdBeforeChange = authenticatedSession.getId();
        Cookie csrfCookie = refreshedCsrfCookie(authenticatedSession);

        mockMvc.perform(patch("/api/users/me/password")
                        .session(authenticatedSession)
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(APPLICATION_JSON)
                        .content(passwordChangeBody()))
                .andExpect(status().isNoContent());

        assertNotEquals(sessionIdBeforeChange, authenticatedSession.getId());
        assertTrue(!authenticatedSession.isInvalid());
        mockMvc.perform(get("/api/test-protected").session(authenticatedSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(81));
    }

    private String passwordChangeBody() {
        return "{"
                + "\"currentPassword\":\"current-pw1\","
                + "\"newPassword\":\"new-password1\"}";
    }

    private Cookie refreshedCsrfCookie(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf").session(session))
                .andExpect(status().isNoContent())
                .andReturn();
        return result.getResponse().getCookie("XSRF-TOKEN");
    }

    private MockHttpSession loginSession(long userId) throws Exception {
        AuthPrincipal principal = new AuthPrincipal(userId, UserRole.OWNER, "김사장");
        when(authService.login(any())).thenReturn(new LoginResult(principal, false));

        Cookie csrfCookie = prepareCsrfCookie();
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(APPLICATION_JSON)
                        .content(loginBody("OWNER")))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) loginResult.getRequest().getSession(false);
    }

    private Cookie prepareCsrfCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn();
        return result.getResponse().getCookie("XSRF-TOKEN");
    }

    private String loginBody(String expectedRole) {
        return "{"
                + "\"loginId\":\"owner01\","
                + "\"password\":\"secret123\","
                + "\"expectedRole\":\"" + expectedRole + "\"}";
    }

    private String signupBody() {
        return "{"
                + "\"loginId\":\"owner01\","
                + "\"password\":\"secret123\","
                + "\"passwordConfirm\":\"secret123\","
                + "\"name\":\"김사장\","
                + "\"email\":\"owner@example.invalid\","
                + "\"role\":\"OWNER\"}";
    }

    @Configuration
    @Import(SecurityConfig.class)
    static class RootTestConfig {

        @Bean
        AuthService authService() {
            return mock(AuthService.class);
        }

        @Bean
        AuthSessionManager authSessionManager(CsrfTokenRepository csrfTokenRepository) {
            return new AuthSessionManager(csrfTokenRepository);
        }
    }

    @Configuration
    @EnableWebMvc
    static class ServletTestConfig {

        @Bean
        AuthController authController(
                AuthService authService,
                AuthSessionManager authSessionManager) {
            return new AuthController(authService, authSessionManager);
        }

        @Bean
        UserController userController(AuthSessionManager authSessionManager) {
            return new UserController(mock(UserService.class), authSessionManager);
        }

        @Bean
        CommonExceptionHandler commonExceptionHandler() {
            return new CommonExceptionHandler();
        }

        @Bean
        ProtectedProbeController protectedProbeController() {
            return new ProtectedProbeController();
        }
    }

    /**
     * 보호 API가 인증 Principal만으로 사용자를 식별하는지 확인하는 테스트 전용 Endpoint입니다.
     */
    @RestController
    static class ProtectedProbeController {

        @GetMapping("/api/test-protected")
        Map<String, Long> whoAmI(Authentication authentication) {
            return Map.of("userId", AuthPrincipals.resolve(authentication).getUserId());
        }
    }
}
