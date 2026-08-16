package com.gighub.notification.domain;

/**
 * 알림을 만든 도메인 행의 종류입니다(SPEC-382-01의 이벤트 표).
 *
 * <p>이동 대상인 근무와 구분되는 값입니다. 둘을 한 값으로 합치면 같은 근무의 두 번째 정상
 * 이벤트가 중복으로 오인돼 영구 누락됩니다.</p>
 */
public enum NotificationSourceType {

    WORK_CASE,
    ESCROW,
    SETTLEMENT,
    DOCUMENT_SHARE,
    DISPUTE
}
