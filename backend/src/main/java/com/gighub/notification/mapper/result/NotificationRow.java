package com.gighub.notification.mapper.result;

import lombok.Getter;

import java.time.LocalDateTime;

/** {@code notifications} 조회 행입니다. 내부 사용자 ID는 응답으로 나가지 않습니다. */
@Getter
public class NotificationRow {

    private Long notificationId;
    private String notiType;
    private String title;
    private String content;
    private String sourceType;
    private Long sourceId;
    private Long workCaseId;
    private Boolean isRead;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}
