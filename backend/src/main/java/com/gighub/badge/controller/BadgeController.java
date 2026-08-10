package com.gighub.badge.controller;

import com.gighub.auth.security.AuthPrincipals;
import com.gighub.badge.dto.UserBadgeListResponse;
import com.gighub.badge.service.BadgeQueryService;
import com.gighub.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class BadgeController {

    private final BadgeQueryService badgeQueryService;

    @GetMapping("/api/users/me/badges")
    public ResponseEntity<ApiResponse<UserBadgeListResponse>> getMyBadge(
            Authentication authentication) {
        Long loginUserId = AuthPrincipals.resolve(authentication).getUserId();

        return ResponseEntity.ok(ApiResponse.of(badgeQueryService.findByUserId(loginUserId)));
    }
}
