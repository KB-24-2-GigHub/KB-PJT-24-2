package com.gighub.notification.service;

import com.gighub.common.exception.ResourceNotFoundException;
import com.gighub.notification.mapper.NotificationMapper;
import com.gighub.notification.service.impl.NotificationServiceImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 읽음 처리의 권한과 재요청 경계를 고정한다.
 *
 * <p>갱신 행이 0인 경우는 "이미 읽음"과 "내 알림이 아님"으로 갈린다. 구분하지 않으면 타인 알림
 * 읽음 시도가 조용히 성공으로 보인다. 목록 조회와 개수는 Mapper Query 자체가
 * {@code recipient_user_id}로 좁혀져 있어 Schema 통합 테스트가 담당한다.</p>
 */
class NotificationServiceImplTest {

    private static final long NOTIFICATION_ID = 4001L;
    private static final long RECIPIENT_ID = 7L;

    private final NotificationMapper notificationMapper = mock(NotificationMapper.class);
    private final NotificationService service = new NotificationServiceImpl(notificationMapper);

    @Test
    void marksOwnUnreadNotificationAsRead() {
        when(notificationMapper.markRead(NOTIFICATION_ID, RECIPIENT_ID)).thenReturn(1);

        service.markRead(NOTIFICATION_ID, RECIPIENT_ID);

        verify(notificationMapper).markRead(NOTIFICATION_ID, RECIPIENT_ID);
    }

    /** 재요청은 성공이며 최초 읽은 시각을 바꾸지 않는다(Mapper의 is_read = 0 조건). */
    @Test
    void treatsRepeatedReadOfOwnNotificationAsSuccess() {
        when(notificationMapper.markRead(NOTIFICATION_ID, RECIPIENT_ID)).thenReturn(0);
        when(notificationMapper.existsForRecipient(NOTIFICATION_ID, RECIPIENT_ID)).thenReturn(1);

        assertDoesNotThrow(() -> service.markRead(NOTIFICATION_ID, RECIPIENT_ID));
    }

    /** 타인 알림은 403이 아니라 404로 거절해 존재를 드러내지 않는다. */
    @Test
    void rejectsAnotherUsersNotificationAsNotFound() {
        when(notificationMapper.markRead(NOTIFICATION_ID, RECIPIENT_ID)).thenReturn(0);
        when(notificationMapper.existsForRecipient(NOTIFICATION_ID, RECIPIENT_ID)).thenReturn(0);

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.markRead(NOTIFICATION_ID, RECIPIENT_ID));
    }

    /**
     * 전체 읽음은 수신자 하나만 넘긴다(SPEC-423-01).
     *
     * <p>행마다 markRead를 반복하면 알림이 쌓인 사용자일수록 왕복이 늘고 중간 실패에서 일부만
     * 읽힌 상태가 남는다. 여기서 고정하는 것은 단일 갱신이라는 점이다.</p>
     */
    @Test
    void marksEveryUnreadNotificationOfOwnerInOneUpdate() {
        when(notificationMapper.markAllRead(RECIPIENT_ID)).thenReturn(3);

        service.markAllRead(RECIPIENT_ID);

        verify(notificationMapper).markAllRead(RECIPIENT_ID);
        verify(notificationMapper, never()).markRead(anyLong(), anyLong());
    }

    /**
     * 안읽음이 0건이어도 성공이다.
     *
     * <p>단건과 달리 대상을 식별자로 지목하지 않으므로 "찾지 못했다"는 상태가 없다. 갱신 행이
     * 0이라는 것은 이미 다 읽었다는 뜻이고 화면이 원한 상태와 같다.</p>
     */
    @Test
    void treatsMarkAllReadWithNothingUnreadAsSuccess() {
        when(notificationMapper.markAllRead(RECIPIENT_ID)).thenReturn(0);

        assertDoesNotThrow(() -> service.markAllRead(RECIPIENT_ID));
    }
}
