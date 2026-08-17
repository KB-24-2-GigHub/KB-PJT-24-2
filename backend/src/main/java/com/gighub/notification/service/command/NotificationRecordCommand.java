package com.gighub.notification.service.command;

import com.gighub.notification.domain.NotificationType;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 한 도메인 이벤트로 만들 알림을 설명합니다.
 *
 * <p>수신자가 둘인 유형은 목록으로 받아 수신자마다 별도 행을 만듭니다. 한 행을 둘이 공유하면
 * 한쪽의 읽음 처리가 다른 쪽의 상태를 덮어씁니다.</p>
 */
@Getter
@Builder
public class NotificationRecordCommand {

    private final NotificationType type;
    /** 이벤트를 만든 도메인 행의 식별자. 이동 대상이 아니라 중복 판정 기준입니다. */
    private final Long sourceId;
    /** 이동 대상 근무 식별자. */
    private final Long workCaseId;
    /** 문구에 넣을 근무 제목. */
    private final String workCaseTitle;
    private final List<Long> recipientUserIds;
}
