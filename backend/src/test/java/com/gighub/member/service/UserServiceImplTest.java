package com.gighub.member.service;

import com.gighub.common.exception.ForbiddenException;
import com.gighub.common.exception.ResourceNotFoundException;
import com.gighub.common.exception.ValidationException;
import com.gighub.member.domain.User;
import com.gighub.member.domain.UserRole;
import com.gighub.member.domain.UserStatus;
import com.gighub.member.dto.UserProfileResponse;
import com.gighub.member.mapper.UserMapper;
import com.gighub.member.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceImplTest {

    private static final String CURRENT_PASSWORD = "current-password1";
    private static final String NEW_PASSWORD = "new-password1";

    private final UserMapper userMapper = mock(UserMapper.class);
    // 실제 BCrypt로 검증한다. Stub은 "Hash로 저장했는지"를 증명하지 못한다.
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final UserServiceImpl service = new UserServiceImpl(userMapper, passwordEncoder);

    @Test
    void returnsApprovedProfileFieldsForOwner() {
        when(userMapper.findProfileById(42L)).thenReturn(owner());

        UserProfileResponse response = service.getProfile(42L);

        assertEquals("owner01", response.getLoginId());
        assertEquals("owner@example.com", response.getEmail());
        assertEquals("김사장", response.getName());
        assertEquals("01012345678", response.getPhone());
        assertEquals(UserRole.OWNER, response.getRole());
        assertEquals(UserStatus.ACTIVE, response.getStatus());
    }

    @Test
    void returnsProfileForWorker() {
        User worker = owner();
        worker.setLoginId("worker01");
        worker.setRole(UserRole.WORKER);
        when(userMapper.findProfileById(43L)).thenReturn(worker);

        UserProfileResponse response = service.getProfile(43L);

        assertEquals("worker01", response.getLoginId());
        assertEquals(UserRole.WORKER, response.getRole());
    }

    @Test
    void keepsPhoneNullWhenNotRegistered() {
        User user = owner();
        user.setPhone(null);
        when(userMapper.findProfileById(42L)).thenReturn(user);

        assertNull(service.getProfile(42L).getPhone());
    }

    @Test
    void rejectsMissingUser() {
        when(userMapper.findProfileById(99L)).thenReturn(null);

        assertThrows(ResourceNotFoundException.class, () -> service.getProfile(99L));
    }

    @Test
    void returnsRefreshedProfileAfterPhoneUpdate() {
        User updated = owner();
        updated.setPhone("01099998888");
        when(userMapper.updatePhone(42L, "01099998888")).thenReturn(1);
        when(userMapper.findProfileById(42L)).thenReturn(updated);

        UserProfileResponse response = service.updatePhone(42L, "01099998888");

        assertEquals("01099998888", response.getPhone());
        assertEquals("owner01", response.getLoginId());
    }

    @Test
    void rejectsPhoneUpdateForMissingUser() {
        when(userMapper.updatePhone(99L, "01099998888")).thenReturn(0);

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.updatePhone(99L, "01099998888"));
    }

    @Test
    void storesNewPasswordAsBcryptHashWhenCurrentPasswordMatches() {
        when(userMapper.findById(42L)).thenReturn(authenticatedOwner(CURRENT_PASSWORD));
        when(userMapper.updatePassword(anyLong(), anyString())).thenReturn(1);

        service.changePassword(42L, CURRENT_PASSWORD, NEW_PASSWORD);

        ArgumentCaptor<String> savedHash = ArgumentCaptor.forClass(String.class);
        verify(userMapper).updatePassword(eq(42L), savedHash.capture());
        assertNotEquals(NEW_PASSWORD, savedHash.getValue());
        assertTrue(savedHash.getValue().startsWith("$2"));
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, savedHash.getValue()));
        assertFalse(passwordEncoder.matches(CURRENT_PASSWORD, savedHash.getValue()));
    }

    @Test
    void rejectsWrongCurrentPasswordWithCurrentPasswordFieldError() {
        when(userMapper.findById(42L)).thenReturn(authenticatedOwner(CURRENT_PASSWORD));

        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> service.changePassword(42L, "wrong-password1", NEW_PASSWORD));

        // 화면이 이 필드명으로 오류를 귀속시키므로 정확히 이 값이어야 한다.
        assertEquals(1, exception.getFieldErrors().size());
        assertEquals("currentPassword", exception.getFieldErrors().get(0).getField());
        verify(userMapper, never()).updatePassword(anyLong(), anyString());
    }

    @Test
    void keepsPasswordsOutOfFailureMessages() {
        when(userMapper.findById(42L)).thenReturn(authenticatedOwner(CURRENT_PASSWORD));

        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> service.changePassword(42L, "wrong-password1", NEW_PASSWORD));

        String exposed = exception.getMessage()
                + exception.getFieldErrors().get(0).getReason();
        assertFalse(exposed.contains("wrong-password1"));
        assertFalse(exposed.contains(NEW_PASSWORD));
    }

    @Test
    void rejectsPasswordChangeWhenAccountIsNoLongerActive() {
        User locked = authenticatedOwner(CURRENT_PASSWORD);
        locked.setStatus(UserStatus.LOCKED);
        when(userMapper.findById(42L)).thenReturn(locked);

        assertThrows(
                ForbiddenException.class,
                () -> service.changePassword(42L, CURRENT_PASSWORD, NEW_PASSWORD));
        verify(userMapper, never()).updatePassword(anyLong(), anyString());
    }

    @Test
    void rejectsPasswordChangeForMissingUser() {
        when(userMapper.findById(99L)).thenReturn(null);

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.changePassword(99L, CURRENT_PASSWORD, NEW_PASSWORD));
        verify(userMapper, never()).updatePassword(anyLong(), anyString());
    }

    private User authenticatedOwner(String rawPassword) {
        User user = owner();
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        return user;
    }

    private User owner() {
        User user = new User();
        user.setId(42L);
        user.setLoginId("owner01");
        user.setEmail("owner@example.com");
        user.setName("김사장");
        user.setPhone("01012345678");
        user.setRole(UserRole.OWNER);
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }
}