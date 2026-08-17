package com.gighub.notification.mapper.command;

import lombok.Builder;
import lombok.Getter;

/** {@code notifications} 한 행의 삽입 값입니다. */
@Getter
@Builder
public class NotificationInsert {

    private final Long recipientUserId;
    private final String notiType;
    private final String sourceType;
    private final Long sourceId;
    private final Long workCaseId;
    private final String title;
    private final String content;
}
