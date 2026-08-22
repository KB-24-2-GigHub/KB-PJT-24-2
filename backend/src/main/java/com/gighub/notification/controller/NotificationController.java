package com.gighub.notification.controller;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.auth.security.AuthPrincipals;
import com.gighub.common.api.ApiResponse;
import com.gighub.common.api.PageRequests;
import com.gighub.common.api.PageResponse;
import com.gighub.notification.dto.NotificationListItemResponse;
import com.gighub.notification.dto.UnreadCountResponse;
import com.gighub.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
 * 인증 사용자 본인의 알림 목록·안읽음 개수 조회와 읽음 처리입니다(SPEC-382-01, SPEC-423-01).
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
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            Authentication authentication) {
        PageRequests.validate(page, size);
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);
        return ApiResponse.of(
                notificationService.findPage(principal.getUserId(), page, size, unreadOnly));
    }

    @GetMapping("/api/notifications/unread-count")
    public ApiResponse<UnreadCountResponse> countUnread(Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);
        return ApiResponse.of(
                new UnreadCountResponse(notificationService.countUnread(principal.getUserId())));
    }

    @ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "204", description = "알림 읽음 처리 완료"))
    @PatchMapping("/api/notifications/{notificationId}/read")
    public ResponseEntity<Void> markRead(
            @PathVariable Long notificationId,
            Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);
        notificationService.markRead(notificationId, principal.getUserId());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    /**
     * 본인의 안읽음 알림을 모두 읽음으로 바꿉니다(SPEC-423-01).
     *
     * <p>단건 경로가 {@code /{notificationId}/read}로 두 Segment라 {@code /read-all}과 겹치지
     * 않습니다. 처리 건수를 응답에 싣지 않습니다. 화면이 필요로 하는 것은 갱신된 안읽음 개수이고
     * 그것은 이미 별도 Operation이 소유합니다.</p>
     */
    @ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "204", description = "전체 알림 읽음 처리 완료"))
    @PatchMapping("/api/notifications/read-all")
    public ResponseEntity<Void> markAllRead(Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);
        notificationService.markAllRead(principal.getUserId());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
