package com.gighub.notification.dto;

import lombok.Getter;

/**
 * {@code GET /api/notifications/unread-count} 응답입니다.
 *
 * <p>안읽음 개수를 목록 Envelope에 덧붙이지 않고 별도 Endpoint로 둡니다. 공통
 * {@code {content,page}} 목록 계약에 예외를 만들지 않고, 헤더 종 아이콘은 목록을 열지 않은
 * 상태에서도 개수만 필요하기 때문입니다.</p>
 */
@Getter
public class UnreadCountResponse {

    private final long unreadCount;

    public UnreadCountResponse(long unreadCount) {
        this.unreadCount = unreadCount;
    }
}
