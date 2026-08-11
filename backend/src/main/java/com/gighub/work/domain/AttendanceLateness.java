package com.gighub.work.domain;

import java.time.Duration;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * 성공 CHECK_IN 시각에서 파생한 지각 여부와 분수입니다.
 *
 * <p>API_SPEC이 고정한 계약대로 {@code attemptedAt > startsAt}이면 지각이며, 분 단위 차이는
 * 올림합니다. 지각은 {@code work_cases}에 저장하는 상태가 아니라 조회 시점에 항상 다시
 * 계산하는 파생값입니다. {@link com.gighub.attendance.service.AttendanceScanExecutor}가 스캔
 * 응답 한 건을 위해 같은 공식을 쓰지만, 이 클래스는 조회 API가 매번 다시 계산할 때 쓰는
 * 별도 구현입니다.</p>
 */
@Getter
@Builder
@AllArgsConstructor
public final class AttendanceLateness {

    private final boolean late;
    private final int lateMinutes;

    private static final AttendanceLateness NOT_LATE =
            new AttendanceLateness(false, 0);

    /**
     * @param startsAt            근무 예정 시작 시각
     * @param checkInAttemptedAt  CHECK_IN 성공 요청의 서버 수신 시각. 아직 출근하지 않았으면
     *                            {@code null}
     */
    public static AttendanceLateness from(LocalDateTime startsAt, LocalDateTime checkInAttemptedAt) {
        if (checkInAttemptedAt == null || !checkInAttemptedAt.isAfter(startsAt)) {
            return NOT_LATE;
        }
        int minutes = (int) Math.ceil(
                Duration.between(startsAt, checkInAttemptedAt).toNanos() / 60_000_000_000d);
        return AttendanceLateness.builder()
                .late(true)
                .lateMinutes(minutes)
                .build();
    }
}
