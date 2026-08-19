package com.gighub.notification.mapper;

import com.gighub.notification.mapper.command.NotificationInsert;
import com.gighub.notification.mapper.result.NotificationRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * {@code notifications}의 유일한 허용 writer입니다({@code MODULE_BOUNDARIES.json}).
 *
 * <p>중복 판정은 여기서 미리 조회해 확인하지 않고 {@code uk_notifications_event} 위반을
 * 받아서 처리합니다. 선검사는 동시 적재에서 뚫립니다.</p>
 */
@Mapper
public interface NotificationMapper {

    int insert(NotificationInsert command);

    List<NotificationRow> findPageByRecipient(
            @Param("recipientUserId") Long recipientUserId,
            @Param("size") int size,
            @Param("offset") int offset,
            @Param("unreadOnly") boolean unreadOnly);

    long countByRecipient(
            @Param("recipientUserId") Long recipientUserId,
            @Param("unreadOnly") boolean unreadOnly);

    long countUnreadByRecipient(@Param("recipientUserId") Long recipientUserId);

    /** 본인 알림만 읽음으로 바꿉니다. 이미 읽은 행은 갱신하지 않아 읽음 시각이 보존됩니다. */
    int markRead(
            @Param("notificationId") Long notificationId,
            @Param("recipientUserId") Long recipientUserId);

    /**
     * 본인의 안읽음 알림을 한 번의 갱신으로 모두 읽음으로 바꿉니다.
     *
     * <p>행마다 {@link #markRead}를 반복 호출하지 않습니다. 알림이 쌓인 사용자일수록 왕복이
     * 늘고, 중간에 실패하면 일부만 읽힌 상태가 남습니다.</p>
     */
    int markAllRead(@Param("recipientUserId") Long recipientUserId);

    /** 읽음 처리 대상이 본인 알림으로 존재하는지 확인합니다. 없으면 404로 구분합니다. */
    int existsForRecipient(
            @Param("notificationId") Long notificationId,
            @Param("recipientUserId") Long recipientUserId);
}
