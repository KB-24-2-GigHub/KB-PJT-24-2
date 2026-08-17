package com.gighub.notification.controller;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.auth.security.AuthPrincipals;
import com.gighub.notification.sse.NotificationEmitterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 인증 사용자 본인의 알림 스트림 구독입니다(#386).
 *
 * <p>구독 대상은 인증 Principal에서만 얻습니다. 사용자 ID를 Request로 받으면 타인의 알림 신호를
 * 엿듣는 경로가 생깁니다.</p>
 *
 * <p>이 Endpoint가 죽어도 {@code GET /api/notifications}와 읽음 처리는 그대로 동작합니다. SSE는
 * 새로고침을 없애는 개선이지 알림 기능의 전제가 아닙니다.</p>
 */
@RestController
@RequiredArgsConstructor
public class NotificationStreamController {

    /**
     * nginx는 기본적으로 응답을 Buffering합니다({@code proxy_buffering on}). 이 헤더가 없으면
     * 배포 환경에서만 이벤트가 도달하지 않습니다. nginx 설정 파일은 certbot이 관리하므로
     * 애플리케이션 헤더로 해결합니다(#358).
     */
    private static final String ACCEL_BUFFERING_HEADER = "X-Accel-Buffering";

    private final NotificationEmitterRegistry emitterRegistry;

    @GetMapping(value = "/api/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> subscribe(Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);
        return ResponseEntity.ok()
                .header(ACCEL_BUFFERING_HEADER, "no")
                .cacheControl(CacheControl.noCache())
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitterRegistry.subscribe(principal.getUserId()));
    }
}
