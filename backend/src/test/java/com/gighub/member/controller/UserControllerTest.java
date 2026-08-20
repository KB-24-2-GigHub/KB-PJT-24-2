package com.gighub.member.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.auth.security.AuthSessionManager;
import com.gighub.common.exception.CommonExceptionHandler;
import com.gighub.common.exception.ValidationException;
import com.gighub.member.domain.User;
import com.gighub.member.domain.UserRole;
import com.gighub.member.domain.UserStatus;
import com.gighub.member.dto.UserProfileResponse;
import com.gighub.member.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    private static final Long USER_ID = 42L;

    @Mock
    private UserService userService;

    private MockMvc mockMvc;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new UserController(
                        userService,
                        // 실제 구현으로 Session ID 회전을 확인한다. Mock은 회전을 증명하지 못한다.
                        new AuthSessionManager(mock(CsrfTokenRepository.class))))
                .setControllerAdvice(new CommonExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .build();

        AuthPrincipal principal = new AuthPrincipal(USER_ID, UserRole.OWNER, "김사장");
        authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    @Test
    void returnsApprovedProfileFieldsOnly() throws Exception {
        when(userService.getProfile(USER_ID)).thenReturn(profile("01012345678"));

        mockMvc.perform(get("/api/users/me").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loginId").value("owner01"))
                .andExpect(jsonPath("$.data.email").value("owner@example.com"))
                .andExpect(jsonPath("$.data.name").value("김사장"))
                .andExpect(jsonPath("$.data.phone").value("01012345678"))
                .andExpect(jsonPath("$.data.role").value("OWNER"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void rejectsProfileReadWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void updatesPhoneFromAuthenticatedPrincipalOnly() throws Exception {
        when(userService.updatePhone(eq(USER_ID), eq("01099998888")))
                .thenReturn(profile("01099998888"));

        mockMvc.perform(patch("/api/users/me")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"010-9999-8888\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("01099998888"));
    }

    @Test
    void rejectsForbiddenProfileField() throws Exception {
        mockMvc.perform(patch("/api/users/me")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"01012345678\",\"name\":\"해커\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(userService, never()).updatePhone(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void rejectsPhoneOutsideApprovedFormat() throws Exception {
        mockMvc.perform(patch("/api/users/me")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"123-456-789\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void changesPasswordFromAuthenticatedPrincipalAndRotatesSessionId() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String sessionIdBeforeChange = session.getId();

        mockMvc.perform(patch("/api/users/me/password")
                        .principal(authentication)
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody("current-pw1", "new-password1")))
                .andExpect(status().isNoContent());

        verify(userService).changePassword(USER_ID, "current-pw1", "new-password1");
        assertNotEquals(sessionIdBeforeChange, session.getId());
    }

    @Test
    void rejectsPasswordChangeWithoutAuthentication() throws Exception {
        mockMvc.perform(patch("/api/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody("current-pw1", "new-password1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verify(userService, never()).changePassword(anyLong(), anyString(), anyString());
    }

    @Test
    void rejectsPasswordChangeBodyCarryingUserId() throws Exception {
        mockMvc.perform(patch("/api/users/me/password")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"current-pw1\","
                                + "\"newPassword\":\"new-password1\",\"userId\":9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(userService, never()).changePassword(anyLong(), anyString(), anyString());
    }

    @Test
    void rejectsNewPasswordShorterThanApprovedMinimum() throws Exception {
        rejectsNewPassword("a".repeat(7));
    }

    @Test
    void rejectsNewPasswordLongerThanApprovedMaximum() throws Exception {
        rejectsNewPassword("a".repeat(65));
    }

    /** 한글 24자는 UTF-8 72byte로 승인 경계의 마지막 통과 값이다. */
    @Test
    void acceptsNewPasswordExactlyAtUtf8ByteLimit() throws Exception {
        String atLimit = "가".repeat(24);
        assertEquals(72, atLimit.getBytes(StandardCharsets.UTF_8).length);

        mockMvc.perform(patch("/api/users/me/password")
                        .principal(authentication)
                        .session(new MockHttpSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody("current-pw1", atLimit)))
                .andExpect(status().isNoContent());

        // 절단하지 않고 원문 그대로 Service에 전달해야 한다.
        ArgumentCaptor<String> delivered = ArgumentCaptor.forClass(String.class);
        verify(userService).changePassword(eq(USER_ID), eq("current-pw1"), delivered.capture());
        assertEquals(atLimit, delivered.getValue());
    }

    /** 한글 25자는 문자 수 경계는 통과하지만 75byte라 거부돼야 한다. */
    @Test
    void rejectsNewPasswordOverUtf8ByteLimitWithoutTruncating() throws Exception {
        String overLimit = "가".repeat(25);
        assertEquals(75, overLimit.getBytes(StandardCharsets.UTF_8).length);

        rejectsNewPassword(overLimit);
    }

    @Test
    void reportsCurrentPasswordMismatchAsFieldErrorAndKeepsSessionId() throws Exception {
        doThrow(new ValidationException(
                "입력값을 확인해 주세요.", "currentPassword", "현재 비밀번호가 일치하지 않습니다."))
                .when(userService).changePassword(USER_ID, "wrong-pw1", "new-password1");
        MockHttpSession session = new MockHttpSession();
        String sessionIdBeforeChange = session.getId();

        mockMvc.perform(patch("/api/users/me/password")
                        .principal(authentication)
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody("wrong-pw1", "new-password1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("currentPassword"))
                .andExpect(jsonPath("$.fieldErrors[0].reason")
                        .value("현재 비밀번호가 일치하지 않습니다."));

        // 변경이 실패했으면 Session ID도 그대로여야 한다.
        assertEquals(sessionIdBeforeChange, session.getId());
    }

    /**
     * Session이 없어도 변경 자체는 성공해야 합니다.
     *
     * <p>회전할 Session이 없다는 이유로 이미 Commit된 비밀번호 변경을 실패로 응답하면
     * 사용자는 바뀐 비밀번호를 모른 채 실패 화면을 보게 됩니다.</p>
     *
     * @throws Exception MockMvc 요청 실행에 실패한 경우
     */
    @Test
    void changesPasswordEvenWhenRequestCarriesNoSession() throws Exception {
        mockMvc.perform(patch("/api/users/me/password")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody("current-pw1", "new-password1")))
                .andExpect(status().isNoContent());

        verify(userService).changePassword(USER_ID, "current-pw1", "new-password1");
    }

    /** 비밀번호는 trim·대소문자 변환 없이 원문 그대로 전달돼야 한다(DEC-AUTH-INPUT). */
    @Test
    void deliversPasswordsWithoutTrimmingOrCaseChange() throws Exception {
        String padded = "  Mixed Case PW  ";

        mockMvc.perform(patch("/api/users/me/password")
                        .principal(authentication)
                        .session(new MockHttpSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody(padded, padded)))
                .andExpect(status().isNoContent());

        verify(userService).changePassword(USER_ID, padded, padded);
    }

    @Test
    void rejectsBlankCurrentPassword() throws Exception {
        mockMvc.perform(patch("/api/users/me/password")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody("   ", "new-password1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(userService, never()).changePassword(anyLong(), anyString(), anyString());
    }

    private void rejectsNewPassword(String newPassword) throws Exception {
        mockMvc.perform(patch("/api/users/me/password")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody("current-pw1", newPassword)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(userService, never()).changePassword(anyLong(), anyString(), anyString());
    }

    private String passwordBody(String currentPassword, String newPassword) throws Exception {
        return new ObjectMapper().writeValueAsString(
                java.util.Map.of("currentPassword", currentPassword, "newPassword", newPassword));
    }

    private UserProfileResponse profile(String phone) {
        User user = new User();
        user.setId(USER_ID);
        user.setLoginId("owner01");
        user.setEmail("owner@example.com");
        user.setName("김사장");
        user.setPhone(phone);
        user.setRole(UserRole.OWNER);
        user.setStatus(UserStatus.ACTIVE);
        return UserProfileResponse.from(user);
    }
}