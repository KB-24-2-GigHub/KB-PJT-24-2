package com.gighub.member.service;

import com.gighub.common.exception.ConflictException;
import com.gighub.common.exception.ForbiddenException;
import com.gighub.common.exception.ResourceNotFoundException;
import com.gighub.common.exception.ValidationException;
import com.gighub.member.domain.User;
import com.gighub.member.domain.UserRole;
import com.gighub.member.domain.UserStatus;
import com.gighub.member.dto.UserProfileResponse;
import com.gighub.member.mapper.UserMapper;
import com.gighub.member.service.impl.UserServiceImpl;
import com.gighub.wallet.dto.WalletBalanceResponse;
import com.gighub.wallet.service.WalletQueryService;
import com.gighub.work.service.WorkParticipationQueryService;
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
    private final WalletQueryService walletQueryService = mock(WalletQueryService.class);
    private final WorkParticipationQueryService workParticipationQueryService =
            mock(WorkParticipationQueryService.class);
    private final UserServiceImpl service = new UserServiceImpl(
            userMapper, passwordEncoder, walletQueryService, workParticipationQueryService);

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

    @Test
    void withdrawsActiveUserWhenPasswordMatchesAndNothingIsLeftBehind() {
        givenWithdrawableOwner();
        when(userMapper.withdraw(42L)).thenReturn(1);

        service.withdraw(42L, CURRENT_PASSWORD);

        verify(userMapper).withdraw(42L);
    }

    @Test
    void rejectsWrongPasswordWithPasswordFieldError() {
        givenWithdrawableOwner();

        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> service.withdraw(42L, "wrong-password1"));

        // 화면이 이 필드명으로 오류를 귀속시키므로 정확히 이 값이어야 한다.
        assertEquals(1, exception.getFieldErrors().size());
        assertEquals("password", exception.getFieldErrors().get(0).getField());
        verify(userMapper, never()).withdraw(anyLong());
    }

    @Test
    void keepsPasswordOutOfFailureMessages() {
        givenWithdrawableOwner();

        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> service.withdraw(42L, "wrong-password1"));

        String exposed = exception.getMessage()
                + exception.getFieldErrors().get(0).getReason();
        assertFalse(exposed.contains("wrong-password1"));
    }

    /**
     * 비밀번호가 틀리면 남은 근무·잔액을 조회하지 않아야 합니다.
     *
     * <p>조회하면 남의 Session을 쥔 호출자가 비밀번호를 모르는 채로 응답 차이만 보고
     * 그 사람의 진행 근무·잔액 유무를 알아낼 수 있습니다.</p>
     */
    @Test
    void doesNotProbeLeftoversBeforePasswordMatches() {
        givenWithdrawableOwner();

        assertThrows(
                ValidationException.class,
                () -> service.withdraw(42L, "wrong-password1"));

        verify(workParticipationQueryService, never()).countUnfinished(anyLong());
        verify(walletQueryService, never()).getBalanceSnapshot(anyLong());
    }

    @Test
    void rejectsWithdrawalWhileUnfinishedWorkCaseRemains() {
        givenWithdrawableOwner();
        when(workParticipationQueryService.countUnfinished(42L)).thenReturn(1);

        assertThrows(ConflictException.class, () -> service.withdraw(42L, CURRENT_PASSWORD));
        verify(userMapper, never()).withdraw(anyLong());
    }

    @Test
    void rejectsWithdrawalWhileEscrowRemains() {
        givenWithdrawableOwner();
        when(walletQueryService.getBalanceSnapshot(42L)).thenReturn(balance(0L, 300_000L));

        assertThrows(ConflictException.class, () -> service.withdraw(42L, CURRENT_PASSWORD));
        verify(userMapper, never()).withdraw(anyLong());
    }

    @Test
    void rejectsWithdrawalWhileWalletBalanceRemains() {
        givenWithdrawableOwner();
        when(walletQueryService.getBalanceSnapshot(42L)).thenReturn(balance(1_000L, 0L));

        assertThrows(ConflictException.class, () -> service.withdraw(42L, CURRENT_PASSWORD));
        verify(userMapper, never()).withdraw(anyLong());
    }

    /** 지갑이 없는 사용자는 잔액도 예치금도 없다 — null을 0으로 읽어 탈퇴를 막지 않는다. */
    @Test
    void withdrawsUserWhoNeverHadWallet() {
        when(userMapper.findById(42L)).thenReturn(authenticatedOwner(CURRENT_PASSWORD));
        when(workParticipationQueryService.countUnfinished(42L)).thenReturn(0);
        // 지갑이 없으면 wallet 경계가 0을 담아 돌려준다(getBalanceSnapshot 계약).
        when(walletQueryService.getBalanceSnapshot(42L)).thenReturn(balance(0L, 0L));
        when(userMapper.withdraw(42L)).thenReturn(1);

        service.withdraw(42L, CURRENT_PASSWORD);

        verify(userMapper).withdraw(42L);
    }

    @Test
    void rejectsWithdrawalWhenAccountIsNoLongerActive() {
        User withdrawn = authenticatedOwner(CURRENT_PASSWORD);
        withdrawn.setStatus(UserStatus.WITHDRAWN);
        when(userMapper.findById(42L)).thenReturn(withdrawn);

        assertThrows(ForbiddenException.class, () -> service.withdraw(42L, CURRENT_PASSWORD));
        verify(userMapper, never()).withdraw(anyLong());
    }

    /**
     * 확인과 갱신 사이에 다른 요청이 먼저 탈퇴시킨 경우입니다.
     *
     * <p>Mapper가 0행을 돌려주는데 204로 응답하면, 두 번째 요청은 자기가 탈퇴시킨 것으로
     * 오해합니다. 갱신 건수를 그대로 믿고 충돌로 끝냅니다.</p>
     */
    @Test
    void rejectsWithdrawalLosingConcurrentRace() {
        givenWithdrawableOwner();
        when(userMapper.withdraw(42L)).thenReturn(0);

        assertThrows(ConflictException.class, () -> service.withdraw(42L, CURRENT_PASSWORD));
    }

    @Test
    void rejectsWithdrawalForMissingUser() {
        when(userMapper.findById(99L)).thenReturn(null);

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.withdraw(99L, CURRENT_PASSWORD));
        verify(userMapper, never()).withdraw(anyLong());
    }

    /** 비밀번호가 맞고 남은 것도 없는 기본 상태. 각 테스트는 막고 싶은 조건만 덮어쓴다. */
    private void givenWithdrawableOwner() {
        when(userMapper.findById(42L)).thenReturn(authenticatedOwner(CURRENT_PASSWORD));
        when(workParticipationQueryService.countUnfinished(42L)).thenReturn(0);
        when(walletQueryService.getBalanceSnapshot(42L)).thenReturn(balance(0L, 0L));
    }

    private WalletBalanceResponse balance(long available, long locked) {
        return WalletBalanceResponse.of("KRW", available, locked);
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