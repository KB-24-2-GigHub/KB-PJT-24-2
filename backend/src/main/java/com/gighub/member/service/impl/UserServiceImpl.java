package com.gighub.member.service.impl;

import com.gighub.common.exception.ForbiddenException;
import com.gighub.common.exception.ResourceNotFoundException;
import com.gighub.common.exception.ValidationException;
import com.gighub.member.domain.User;
import com.gighub.member.domain.UserStatus;
import com.gighub.member.dto.UserProfileResponse;
import com.gighub.member.mapper.UserMapper;
import com.gighub.member.service.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 인증 Principal의 userId만으로 본인 프로필과 비밀번호를 다룹니다. */
@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
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
     * <p>원문·Hash·userId를 로그나 예외 메시지에 남기지 않습니다.</p>
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
}
