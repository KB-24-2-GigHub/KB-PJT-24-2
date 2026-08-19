package com.gighub.notification.service.impl;

import com.gighub.common.api.ApiTimes;
import com.gighub.common.api.PageRequests;
import com.gighub.common.api.PageResponse;
import com.gighub.common.exception.ResourceNotFoundException;
import com.gighub.notification.dto.NotificationListItemResponse;
import com.gighub.notification.mapper.NotificationMapper;
import com.gighub.notification.mapper.result.NotificationRow;
import com.gighub.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 수신자 본인의 알림만 다룹니다.
 *
 * <p>모든 Query가 {@code recipient_user_id}로 좁혀져 있어 타인 알림은 조회 결과에 들어오지
 * 않고, 읽음 처리도 대상 행을 찾지 못해 실패합니다. 권한 검사를 조회 뒤 별도 단계로 두면
 * 그 단계를 빠뜨린 경로가 생기므로 조건 자체에 넣습니다.</p>
 */
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationMapper notificationMapper;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NotificationListItemResponse> findPage(
            long recipientUserId,
            int page,
            int size,
            boolean unreadOnly) {
        long totalElements = notificationMapper.countByRecipient(recipientUserId, unreadOnly);
        List<NotificationListItemResponse> content = notificationMapper.findPageByRecipient(
                        recipientUserId,
                        size,
                        Math.toIntExact(PageRequests.offset(page, size)),
                        unreadOnly)
                .stream()
                .map(NotificationServiceImpl::toResponse)
                .toList();
        return PageResponse.of(content, page, size, totalElements);
    }

    @Override
    @Transactional(readOnly = true)
    public long countUnread(long recipientUserId) {
        return notificationMapper.countUnreadByRecipient(recipientUserId);
    }

    /**
     * 이미 읽은 알림에 다시 요청해도 성공이며 최초 읽은 시각을 유지합니다.
     *
     * <p>갱신 행이 0인 경우는 "이미 읽음"과 "내 알림이 아님"으로 갈립니다. 둘을 구분하지 않으면
     * 타인 알림 읽음 시도가 조용히 성공으로 보입니다. 존재 여부를 따로 확인해 없으면 404로
     * 거부하고, 응답은 알림의 존재를 드러내지 않습니다.</p>
     */
    @Override
    @Transactional
    public void markRead(long notificationId, long recipientUserId) {
        if (notificationMapper.markRead(notificationId, recipientUserId) == 1) {
            return;
        }
        if (notificationMapper.existsForRecipient(notificationId, recipientUserId) == 0) {
            throw new ResourceNotFoundException("알림을 찾을 수 없습니다.");
        }
    }

    /**
     * 안읽음이 하나도 없어도 성공입니다.
     *
     * <p>단건 읽음과 달리 대상을 식별자로 지목하지 않으므로 "찾지 못했다"는 상태가 없습니다.
     * 갱신 행이 0이라는 것은 이미 다 읽었다는 뜻이고, 화면이 원한 상태와 같습니다. 이것을
     * 오류로 만들면 호출자가 무해한 결과를 실패로 다뤄야 합니다.</p>
     */
    @Override
    @Transactional
    public void markAllRead(long recipientUserId) {
        notificationMapper.markAllRead(recipientUserId);
    }

    private static NotificationListItemResponse toResponse(NotificationRow row) {
        return new NotificationListItemResponse(
                row.getNotificationId(),
                row.getNotiType(),
                row.getTitle(),
                row.getContent(),
                row.getSourceType(),
                row.getSourceId(),
                row.getWorkCaseId(),
                row.getIsRead(),
                ApiTimes.toInstant(row.getReadAt()),
                ApiTimes.toInstant(row.getCreatedAt())
        );
    }
}
