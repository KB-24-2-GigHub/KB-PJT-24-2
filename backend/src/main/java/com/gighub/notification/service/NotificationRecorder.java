package com.gighub.notification.service;

import com.gighub.notification.service.command.NotificationRecordCommand;

/**
 * 다른 논리 모듈이 알림을 남길 때 쓰는 유일한 공개 Command 경계입니다.
 *
 * <p>알림은 이미 확정된 사실을 알리기만 합니다. 이 호출은 어떤 도메인 상태도 바꾸지 않고,
 * 호출한 도메인 처리의 성공 여부를 결정하지도 않습니다(SPEC-384-01).</p>
 */
public interface NotificationRecorder {

    /**
     * 원인 도메인 Transaction이 Commit된 뒤에 알림을 적재합니다.
     *
     * <p>Transaction 안에서 불러도 즉시 저장하지 않고 Commit 이후로 미룹니다. 호출자는 실패를
     * 다룰 필요가 없으며 이 Method는 예외를 던지지 않습니다. Rollback되면 알림도 남지 않습니다.</p>
     */
    void record(NotificationRecordCommand command);
}
