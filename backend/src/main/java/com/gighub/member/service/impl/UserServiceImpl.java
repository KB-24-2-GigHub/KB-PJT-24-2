package com.gighub.member.service.impl;

import com.gighub.common.exception.ConflictException;
import com.gighub.common.exception.ForbiddenException;
import com.gighub.common.exception.ResourceNotFoundException;
import com.gighub.common.exception.ValidationException;
import com.gighub.member.domain.User;
import com.gighub.member.domain.UserStatus;
import com.gighub.member.dto.UserProfileResponse;
import com.gighub.member.mapper.UserMapper;
import com.gighub.member.service.UserService;
import com.gighub.wallet.dto.WalletBalanceResponse;
import com.gighub.wallet.service.WalletQueryService;
import com.gighub.work.service.WorkParticipationQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 인증 Principal의 userId만으로 본인 프로필과 자격 증명, 탈퇴를 다룹니다. */
@Service
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final WalletQueryService walletQueryService;
    private final WorkParticipationQueryService workParticipationQueryService;

    public UserServiceImpl(
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            WalletQueryService walletQueryService,
            WorkParticipationQueryService workParticipationQueryService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.walletQueryService = walletQueryService;
        this.workParticipationQueryService = workParticipationQueryService;
    }

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(Long userId) {
        User user = userMapper.findProfileById(userId);
        if (user == null) {
            throw new ResourceNotFoundException("사용자를 찾을 수 없습니다.");
        }
        return UserProfileResponse.from(user);
    }

    /**
     * 전화번호를 교체합니다.
     *
     * <p>{@link #changePassword}와 달리 계정 상태를 확인하지 않습니다. 그 가드는 자격 증명에만
     * 적용합니다. {@code AUTH-008}의 프로필 수정 계약에는 상태 조건이 없어서, 승인 없이 여기서
     * 범위를 좁히지 않습니다. 프로필 변경도 활성 계정으로 제한할지는 별도 결정입니다.</p>
     */
    @Override
    @Transactional
    public UserProfileResponse updatePhone(Long userId, String phone) {
        int updated = userMapper.updatePhone(userId, phone);
        if (updated == 0) {
            throw new ResourceNotFoundException("사용자를 찾을 수 없습니다.");
        }
        return UserProfileResponse.from(userMapper.findProfileById(userId));
    }

    /**
     * 현재 비밀번호를 대조한 뒤 새 BCrypt Hash로 교체합니다.
     *
     * <p>비밀번호 원문과 Hash는 로그나 예외 메시지에 남기지 않습니다. 대조 실패는 어느
     * 계정에서 일어났는지 알 수 있어야 사후 추적이 되므로 {@code userId}만 남깁니다.</p>
     *
     * <p>시도 횟수를 제한하지 않습니다. 인증된 Session만 도달하지만 이 Operation은 현재
     * 비밀번호를 반복해서 추측할 수 있는 통로이기도 합니다. 잠금·지연 정책은 승인된 계약이
     * 없어 여기서 정하지 않고, 실패 사실만 남겨 사후에 확인할 수 있게 합니다.</p>
     */
    @Override
    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new ResourceNotFoundException("사용자를 찾을 수 없습니다.");
        }

        // 로그인은 인증하는 순간에만 상태를 봅니다. Session이 살아 있는 동안 계정이 잠기거나
        // 탈퇴하면 그 Session으로 비밀번호를 계속 바꿀 수 있으므로 변경 시점에 다시 확인합니다.
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenException("현재 계정 상태에서는 비밀번호를 변경할 수 없습니다.");
        }

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            log.warn("현재 비밀번호 대조에 실패해 비밀번호 변경을 거절했습니다. userId={}", userId);
            // fieldErrors의 reason은 화면이 그대로 사용자에게 보여주므로 코드가 아닌 문장입니다.
            throw new ValidationException(
                    "입력값을 확인해 주세요.",
                    "currentPassword",
                    "현재 비밀번호가 일치하지 않습니다."
            );
        }

        if (userMapper.updatePassword(userId, passwordEncoder.encode(newPassword)) != 1) {
            throw new IllegalStateException("비밀번호 변경 결과가 올바르지 않습니다.");
        }
    }

    /**
     * 비밀번호를 재확인한 뒤 사용자를 물리 삭제하지 않고 탈퇴 상태로 바꿉니다(AUTH-010).
     *
     * <p>감사·계약·금융·문서 관계는 그대로 두고 {@code users.status}와 {@code deleted_at}만
     * 바꿉니다. 로그인은 {@code ACTIVE}만 통과시키므로 탈퇴 뒤에는 다시 로그인할 수
     * 없습니다.</p>
     *
     * <p>{@link #changePassword}와 같은 이유로 실패 사실만 {@code userId}와 함께 남기고
     * 비밀번호 원문과 Hash는 어디에도 남기지 않습니다.</p>
     */
    @Override
    @Transactional
    public void withdraw(Long userId, String password) {
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new ResourceNotFoundException("사용자를 찾을 수 없습니다.");
        }

        // 자격 증명을 재확인하는 Operation이므로 changePassword와 같은 가드를 같은 등급으로
        // 적용합니다. 이미 탈퇴했거나 잠긴 계정의 살아 있는 Session이 여기로 들어옵니다.
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenException("현재 계정 상태에서는 탈퇴할 수 없습니다.");
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            log.warn("비밀번호 대조에 실패해 회원 탈퇴를 거절했습니다. userId={}", userId);
            // fieldErrors의 reason은 화면이 그대로 사용자에게 보여주므로 코드가 아닌 문장입니다.
            throw new ValidationException(
                    "입력값을 확인해 주세요.",
                    "password",
                    "비밀번호가 일치하지 않습니다."
            );
        }

        requireNothingLeftBehind(userId);

        // status = 'ACTIVE' 조건이 걸린 갱신입니다. 위 확인과 이 갱신 사이에 다른 요청이
        // 먼저 탈퇴시켰다면 0행이 되고, 그 요청을 성공으로 응답하지 않습니다.
        if (userMapper.withdraw(userId) != 1) {
            throw new ConflictException("탈퇴 요청이 이미 처리됐습니다.");
        }
    }

    /**
     * 탈퇴를 막아야 하는 미결 상태를 확인합니다.
     *
     * <p>세 가지를 각각 다른 문장으로 알립니다. "탈퇴할 수 없습니다" 하나로 묶으면 사용자가
     * 무엇을 정리해야 탈퇴되는지 알 수 없어 같은 실패를 반복합니다. 어느 것도 비밀번호
     * 입력값의 문제가 아니므로 {@code fieldErrors}가 아닌 409 본문 메시지로 내보냅니다 —
     * 화면은 이 메시지를 Toast로 띄웁니다.</p>
     */
    private void requireNothingLeftBehind(Long userId) {
        if (workParticipationQueryService.countUnfinished(userId) > 0) {
            throw new ConflictException("진행 중인 근무가 남아 있어 탈퇴할 수 없습니다.");
        }

        // 지갑이 없는 사용자는 잔액도 예치금도 없습니다. 0으로 읽어 탈퇴를 막지 않습니다.
        WalletBalanceResponse balance = walletQueryService.getBalanceSnapshot(userId);
        if (balance.getLockedBalance() > 0) {
            throw new ConflictException("예치 중인 금액이 남아 있어 탈퇴할 수 없습니다.");
        }
        if (balance.getAvailableBalance() > 0) {
            throw new ConflictException("지갑 잔액이 남아 있어 탈퇴할 수 없습니다. 먼저 출금해 주세요.");
        }
    }
}
