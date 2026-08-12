package com.gighub.auth.service.impl;

import com.gighub.auth.dto.SignupRequest;
import com.gighub.auth.dto.LoginRequest;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.auth.service.AuthService;
import com.gighub.auth.service.LoginResult;
import com.gighub.common.exception.AuthRequiredException;
import com.gighub.common.exception.ConflictException;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.member.domain.User;
import com.gighub.member.domain.UserRole;
import com.gighub.member.domain.UserStatus;
import com.gighub.member.mapper.UserMapper;
import com.gighub.wallet.service.WalletProvisionService;
import com.gighub.workplace.service.WorkplaceOwnershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 승인된 인증 계약을 DB 현재 상태로 계산합니다. */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final WorkplaceOwnershipService workplaceOwnershipService;
    private final UserMapper userMapper;
    private final WalletProvisionService walletProvisionService;
    private final PasswordEncoder passwordEncoder;

    @Override
    public boolean isLoginIdAvailable(String loginId) {
        return userMapper.countByLoginId(loginId) == 0;
    }

    @Override
    public boolean isEmailAvailable(String email) {
        return userMapper.countByEmail(email) == 0;
    }

    @Override
    @Transactional
    public Long signup(SignupRequest request) {
        if (!isLoginIdAvailable(request.getLoginId())
                || !isEmailAvailable(request.getEmail())) {
            throw signupConflict();
        }

        User user = new User(
                request.getLoginId(),
                request.getEmail(),
                passwordEncoder.encode(request.getPassword()),
                request.getName(),
                request.getPhone(),
                request.getRole()
        );

        try {
            if (userMapper.insert(user) != 1 || user.getId() == null) {
                throw new IllegalStateException("가입 사용자 저장 결과가 올바르지 않습니다.");
            }
            // 사용자와 기본 지갑은 하나의 가입 단위이므로 어느 한쪽 실패 시 함께 되돌립니다.
            walletProvisionService.provisionKrwWallet(user.getId());
            return user.getId();
        } catch (DuplicateKeyException exception) {
            // 사전 조회 이후 동시 가입이 들어와도 DB Unique 제약을 최종 방어선으로 사용합니다.
            throw signupConflict();
        }
    }

    @Override
    public LoginResult login(LoginRequest request) {
        User user = userMapper.findByLoginId(request.getLoginId());
        // 존재하지 않는 아이디에도 BCrypt 검증 비용을 적용해 응답 시간 차이를 줄입니다.
        String passwordHash = user == null ? DUMMY_PASSWORD_HASH : user.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), passwordHash);

        // 계정 존재·상태·비밀번호 중 어느 조건이 실패했는지 응답으로 구분하지 않습니다.
        if (user == null || user.getStatus() != UserStatus.ACTIVE || !passwordMatches) {
            throw new AuthRequiredException("아이디 또는 비밀번호를 확인해 주세요.");
        }
        if (user.getRole() != request.getExpectedRole()) {
            throw new RoleMismatchException("선택한 역할과 계정 역할이 일치하지 않습니다.");
        }

        AuthPrincipal principal = new AuthPrincipal(user.getId(), user.getRole(), user.getName());
        return new LoginResult(principal, needsWorkplaceSetup(principal));
    }

    @Override
    public boolean needsWorkplaceSetup(AuthPrincipal principal) {
        if (principal.getRole() != UserRole.OWNER) {
            return false;
        }
        // 사업장 생성·비활성화가 즉시 반영되도록 Session에 계산 결과를 저장하지 않습니다.
        return !workplaceOwnershipService.hasActiveOwnedWorkplace(principal.getUserId());
    }

    private ConflictException signupConflict() {
        return new ConflictException("이미 사용 중인 가입 정보입니다.");
    }
}
