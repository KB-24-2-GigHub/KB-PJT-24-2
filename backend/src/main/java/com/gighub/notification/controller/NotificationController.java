package com.gighub.notification.controller;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.auth.security.AuthPrincipals;
import com.gighub.common.api.ApiResponse;
import com.gighub.common.api.PageRequests;
import com.gighub.common.api.PageResponse;
import com.gighub.notification.dto.NotificationListItemResponse;
import com.gighub.notification.dto.UnreadCountResponse;
import com.gighub.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 사용자 본인의 알림 목록·안읽음 개수 조회와 단건 읽음 처리입니다(SPEC-382-01).
 *
 * <p>수신자는 항상 인증 Principal에서 얻습니다. 사용자 ID를 Request에서 받으면 타인 알림 조회
 * 경로가 생깁니다.</p>
 */
@RestController
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/api/notifications")
    public ApiResponse<PageResponse<NotificationListItemResponse>> findPage(
            @RequestParam(defaultValue = PageRequests.DEFAULT_PAGE_TEXT) int page,
            @RequestParam(defaultValue = PageRequests.DEFAULT_SIZE_TEXT) int size,
            Authentication authentication) {
        PageRequests.validate(page, size);
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);
        return ApiResponse.of(notificationService.findPage(principal.getUserId(), page, size));
    }

    @GetMapping("/api/notifications/unread-count")
    public ApiResponse<UnreadCountResponse> countUnread(Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);
        return ApiResponse.of(
                new UnreadCountResponse(notificationService.countUnread(principal.getUserId())));
    }

    @PatchMapping("/api/notifications/{notificationId}/read")
    public ResponseEntity<Void> markRead(
            @PathVariable Long notificationId,
            Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);
        notificationService.markRead(notificationId, principal.getUserId());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
