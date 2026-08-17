package com.gighub.notification.service;

import com.gighub.common.api.PageResponse;
import com.gighub.notification.dto.NotificationListItemResponse;

/** 수신자 본인의 알림 목록·안읽음 개수 조회와 읽음 처리입니다. */
public interface NotificationService {

    PageResponse<NotificationListItemResponse> findPage(long recipientUserId, int page, int size);

    long countUnread(long recipientUserId);

    void markRead(long notificationId, long recipientUserId);
}
