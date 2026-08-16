package com.gighub.notification.dto;

import lombok.Getter;

import java.time.Instant;

/**
 * {@code GET /api/notifications} 목록 Item입니다(SPEC-382-01).
 *
 * <p>{@code isRead}는 {@code Boolean}입니다. 원시 {@code boolean}으로 두면 Lombok이
 * {@code isRead()}를 만들고 Jackson이 이를 {@code read}로 직렬화해 승인된 필드명이 바뀝니다.
 * DB가 {@code NOT NULL}이라 값은 항상 존재합니다.</p>
 */
@Getter
public class NotificationListItemResponse {

    private final Long notificationId;
    private final String notiType;
    private final String title;
    private final String content;
    private final String sourceType;
    private final Long sourceId;
    private final Long workCaseId;
    private final Boolean isRead;
    private final Instant readAt;
    private final Instant createdAt;

    public NotificationListItemResponse(
            Long notificationId,
            String notiType,
            String title,
            String content,
            String sourceType,
            Long sourceId,
            Long workCaseId,
            Boolean isRead,
            Instant readAt,
            Instant createdAt) {
        this.notificationId = notificationId;
        this.notiType = notiType;
        this.title = title;
        this.content = content;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.workCaseId = workCaseId;
        this.isRead = isRead;
        this.readAt = readAt;
        this.createdAt = createdAt;
    }
}
