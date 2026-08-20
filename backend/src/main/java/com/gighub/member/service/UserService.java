package com.gighub.member.service;

import com.gighub.member.dto.UserProfileResponse;

/** 인증 사용자의 프로필 조회·수정 계약입니다. */
public interface UserService {

    UserProfileResponse getProfile(Long userId);

    UserProfileResponse updatePhone(Long userId, String phone);

    void changePassword(Long userId, String currentPassword, String newPassword);

    void withdraw(Long userId, String password);
}