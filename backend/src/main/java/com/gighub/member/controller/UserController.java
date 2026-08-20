package com.gighub.member.controller;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;

import com.gighub.auth.security.AuthPrincipals;
import com.gighub.auth.security.AuthSessionManager;
import com.gighub.common.api.ApiResponse;
import com.gighub.member.dto.PasswordChangeRequest;
import com.gighub.member.dto.UserProfileResponse;
import com.gighub.member.dto.UserProfileUpdateRequest;
import com.gighub.member.dto.WithdrawalRequest;
import com.gighub.member.service.UserService;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final AuthSessionManager authSessionManager;

    // 내 프로필 조회. 요청에서 사용자 ID를 받지 않고 인증 Principal만 사용한다.
    @GetMapping("/api/users/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getMyProfile(
            Authentication authentication) {
        Long userId = AuthPrincipals.resolve(authentication).getUserId();

        return ResponseEntity.ok(ApiResponse.of(userService.getProfile(userId)));
    }

    // 내 전화번호 수정. 승인 명세에 따라 phone 외 필드는 요청 DTO에서 거부된다.
    @PatchMapping("/api/users/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateMyPhone(
            Authentication authentication,
            @Valid @RequestBody UserProfileUpdateRequest request) {
        Long userId = AuthPrincipals.resolve(authentication).getUserId();

        return ResponseEntity.ok(
                ApiResponse.of(userService.updatePhone(userId, request.getPhone())));
    }

    /**
     * 내 비밀번호 변경(AUTH-009). 사용자 ID를 받지 않고 인증 Principal만 사용한다.
     *
     * <p>성공 뒤 Session ID를 회전한다. 자격 증명이 바뀌었으므로 이전 ID를 재사용하지 않되,
     * 인증 상태는 유지해 사용자가 다시 로그인하지 않게 한다.</p>
     */
    // Runtime Swagger가 반환 타입만으로는 204를 추론하지 못하므로 명시한다(#123).
    @ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "204", description = "비밀번호 변경 완료"))
    @PatchMapping("/api/users/me/password")
    public ResponseEntity<Void> changeMyPassword(
            Authentication authentication,
            @Valid @RequestBody PasswordChangeRequest request,
            HttpServletRequest servletRequest) {
        Long userId = AuthPrincipals.resolve(authentication).getUserId();

        userService.changePassword(
                userId, request.getCurrentPassword(), request.getNewPassword());
        authSessionManager.rotateSessionId(servletRequest);

        return ResponseEntity.noContent().build();
    }

    /**
     * 내 회원 탈퇴(AUTH-010). 사용자 ID를 받지 않고 인증 Principal만 사용한다.
     *
     * <p>성공하면 현재 Session을 무효화한다. 탈퇴가 Commit된 뒤에만 무효화하므로, 실패한
     * 요청은 Session을 그대로 두고 사용자가 사유를 보고 다시 시도할 수 있다.</p>
     */
    // Runtime Swagger가 반환 타입만으로는 204를 추론하지 못하므로 명시한다(#123).
    @ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "204", description = "회원 탈퇴 완료"))
    @PostMapping("/api/users/me/withdrawal")
    public ResponseEntity<Void> withdrawMe(
            Authentication authentication,
            @Valid @RequestBody WithdrawalRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        Long userId = AuthPrincipals.resolve(authentication).getUserId();

        userService.withdraw(userId, request.getPassword());
        authSessionManager.logout(servletRequest, servletResponse, authentication);

        return ResponseEntity.noContent().build();
    }
}
