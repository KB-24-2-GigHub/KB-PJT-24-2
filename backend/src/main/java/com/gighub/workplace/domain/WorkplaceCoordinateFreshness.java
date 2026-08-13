package com.gighub.workplace.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * 현장 위치 확정 측정 시각의 신선도 경계입니다(API_SPEC "사업장 출퇴근 위치 확정").
 *
 * <p>근태 스캔의 {@code AttendanceWindowPolicy}와 같은 5분 전~1분 후 경계를 쓰지만, 사업장
 * 모듈이 Attendance 내부 클래스에 의존하지 않도록 이 모듈 안에 독립적으로 둡니다. 두 모듈이
 * 같은 값을 쓰는 것은 계약의 우연한 일치이지 공유 소유는 아닙니다.</p>
 */
public final class WorkplaceCoordinateFreshness {

    private static final Duration MAX_AGE = Duration.ofMinutes(5);
    private static final Duration MAX_SKEW = Duration.ofMinutes(1);

    private WorkplaceCoordinateFreshness() {
    }

    /**
     * 측정 시각이 서버 수신 시각 기준 5분 전부터 1분 후까지인지 판정합니다.
     *
     * @param capturedAt 클라이언트가 보낸 측정 시각
     * @param receivedAt 서버 수신 시각
     * @return 신선하면 {@code true}
     */
    public static boolean isFresh(Instant capturedAt, Instant receivedAt) {
        return capturedAt != null
                && !capturedAt.isBefore(receivedAt.minus(MAX_AGE))
                && !capturedAt.isAfter(receivedAt.plus(MAX_SKEW));
    }
}
