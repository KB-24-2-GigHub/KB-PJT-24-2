package com.gighub.notification.service;

import com.gighub.common.api.PageResponse;
import com.gighub.notification.dto.NotificationListItemResponse;

/** 수신자 본인의 알림 목록·안읽음 개수 조회와 읽음 처리입니다. */
public interface NotificationService {

    PageResponse<NotificationListItemResponse> findPage(
            long recipientUserId,
            int page,
            int size,
            boolean unreadOnly);

    long countUnread(long recipientUserId);

    void markRead(long notificationId, long recipientUserId);

    /** 본인의 안읽음 알림을 모두 읽음으로 바꿉니다. 대상이 없어도 성공입니다(SPEC-423-01). */
    void markAllRead(long recipientUserId);
}
