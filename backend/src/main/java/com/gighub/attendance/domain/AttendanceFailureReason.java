package com.gighub.attendance.domain;

/**
 * 거절된 스캔 시도의 승인된 감사 사유입니다.
 *
 * <p>API_SPEC 6.0.0이 {@code attendance_records.failure_reason}에 남길 값을 다섯 개로
 * 고정했습니다. 상수 이름을 그대로 저장하므로 이름을 바꾸면 이미 쌓인 감사 기록과
 * 어긋납니다.</p>
 *
 * <p>정확히 하나의 근무와 출퇴근 유형을 정한 뒤 발생한 거절만 이 사유로 기록합니다. QR
 * 변조와 후보 없음·복수처럼 신뢰할 근무를 정할 수 없는 요청은 근태 행 없이 보안 로그만
 * 남깁니다.</p>
 */
public enum AttendanceFailureReason {

    /** 위치 정확도가 승인 상한을 넘었습니다. */
    LOCATION_INACCURATE,

    /** 측정 시각이 승인된 신선도 범위를 벗어났습니다. */
    LOCATION_STALE,

    /** 반올림 전 거리가 승인 반경을 넘었습니다. */
    OUTSIDE_RADIUS,

    /** 잠근 뒤 확인한 시간창이 이미 닫혔습니다. */
    TIME_WINDOW_CLOSED,

    /** 상태가 어긋났거나 다른 Key와의 경쟁에서 밀렸습니다. */
    STATE_CONFLICT
}
