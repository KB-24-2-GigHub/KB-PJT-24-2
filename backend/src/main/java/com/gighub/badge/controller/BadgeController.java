package com.gighub.badge.controller;

import com.gighub.auth.security.AuthPrincipals;
import com.gighub.badge.dto.BadgeResponse;
import com.gighub.badge.service.BadgeApplicationService;
import com.gighub.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class BadgeController {

    private final BadgeApplicationService badgeApplicationService;

    @GetMapping("/api/users/me/badge")
    public ResponseEntity<ApiResponse<BadgeResponse>> getMyBadge(Authentication authentication) {
        long userId = AuthPrincipals.resolve(authentication).getUserId();

        return ResponseEntity.ok(
                ApiResponse.of(BadgeResponse.of(badgeApplicationService.recalculate(userId))));
    }
}
