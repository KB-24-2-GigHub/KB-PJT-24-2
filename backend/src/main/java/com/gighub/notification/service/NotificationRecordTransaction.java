package com.gighub.notification.service;

import com.gighub.notification.domain.NotificationType;
import com.gighub.notification.mapper.NotificationMapper;
import com.gighub.notification.mapper.command.NotificationInsert;
import com.gighub.notification.service.command.NotificationRecordCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 한 행의 Transaction 경계입니다.
 *
 * <p>{@code NotificationRecorderImpl}과 분리한 이유는 하나입니다. 같은 Class 안에서 부른
 * {@code @Transactional} Method는 Proxy를 거치지 않아 새 Transaction이 열리지 않습니다. 수신자
 * 한 명의 실패가 나머지 수신자의 알림까지 되돌리지 않으려면 행마다 실제로 경계가 있어야
 * 합니다.</p>
 */
@Service
@RequiredArgsConstructor
public class NotificationRecordTransaction {

    private final NotificationMapper notificationMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertOne(NotificationRecordCommand command, Long recipientUserId) {
        NotificationType type = command.getType();
        notificationMapper.insert(NotificationInsert.builder()
                .recipientUserId(recipientUserId)
                .notiType(type.name())
                .sourceType(type.sourceType().name())
                .sourceId(command.getSourceId())
                .workCaseId(command.getWorkCaseId())
                .title(type.title())
                .content(type.content(command.getWorkCaseTitle()))
                .build());
    }
}
