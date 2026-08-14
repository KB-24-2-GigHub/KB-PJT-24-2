package com.gighub.attendance.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 근태 자동 판정과 QR 스캔이 함께 쓰는 시간 경계입니다.
 *
 * <p>세 경계는 Scheduler와 스캔이 같은 값을 봐야 합니다. 값이 어긋나면 스캔은 받아주는데
 * Scheduler가 결근으로 끝내거나, 반대로 아직 준비되지 않은 근무를 스캔이 먼저 처리하는
 * 모순이 생깁니다.</p>
 *
 * <ul>
 *   <li>{@code READY_LEAD} 시작 전 준비 전이와 출근 스캔이 열리는 시점</li>
 *   <li>{@code NO_SHOW_GRACE} 성공 출근이 없으면 결근으로 끝나는 시점</li>
 *   <li>{@code CHECK_OUT_GRACE} 성공 퇴근이 없으면 퇴근 누락으로 끝나는 시점</li>
 * </ul>
 *
 * <p>DB 비교에 쓰는 경계는 컬럼이 아니라 판정 시각 쪽을 옮겨 계산합니다. 컬럼에 함수를
 * 씌우면 인덱스를 타지 못합니다.</p>
 */
public final class AttendanceWindowPolicy {

    private static final int READY_LEAD_MINUTES = 30;
    private static final int NO_SHOW_GRACE_HOURS = 1;
    private static final int CHECK_OUT_GRACE_HOURS = 2;

    /** 측정 시각이 서버 수신 시각보다 앞설 수 있는 한계입니다. */
    private static final Duration CAPTURE_MAX_AGE = Duration.ofMinutes(5);

    /** 단말 시계가 서버보다 빠를 때 허용하는 한계입니다. */
    private static final Duration CAPTURE_MAX_SKEW = Duration.ofMinutes(1);

    private AttendanceWindowPolicy() {
    }

    /** 출근 스캔과 {@code ACCEPTED -> READY} 전이가 열리는 시점입니다. */
    public static LocalDateTime readyOpensAt(LocalDateTime startsAt) {
        return startsAt.minusMinutes(READY_LEAD_MINUTES);
    }

    /** 성공 출근이 없으면 결근으로 확정되는 시점입니다. */
    public static LocalDateTime noShowAt(LocalDateTime startsAt, LocalDateTime endsAt) {
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(endsAt, "endsAt");
        LocalDateTime graceBoundary = startsAt.plusHours(NO_SHOW_GRACE_HOURS);
        // 짧은 근무가 종료된 뒤에도 출근을 기다리지 않도록 두 경계 중 먼저 온 시각을 사용합니다.
        return endsAt.isBefore(graceBoundary) ? endsAt : graceBoundary;
    }

    /** 성공 퇴근이 없으면 퇴근 누락으로 확정되는 시점입니다. */
    public static LocalDateTime checkOutMissingAt(LocalDateTime endsAt) {
        return endsAt.plusHours(CHECK_OUT_GRACE_HOURS);
    }

    /**
     * 출근 스캔 후보의 {@code starts_at} 상한입니다.
     *
     * <p>{@code readyOpensAt(starts_at) <= attemptedAt}을 {@code starts_at} 기준으로
     * 옮긴 값입니다.</p>
     */
    public static LocalDateTime readyLatestStartsAt(LocalDateTime attemptedAt) {
        return attemptedAt.plusMinutes(READY_LEAD_MINUTES);
    }

    /**
     * 출근 스캔 후보의 {@code starts_at} 하한입니다.
     *
     * <p>{@code attemptedAt < starts_at + 1시간}을 옮긴 값이며 경계는 열린 구간입니다.</p>
     */
    public static LocalDateTime readyEarliestStartsAt(LocalDateTime attemptedAt) {
        return attemptedAt.minusHours(NO_SHOW_GRACE_HOURS);
    }

    /** Scheduler가 조회할 NO_SHOW 시작·종료 경계를 순서가 뒤바뀌지 않는 값으로 묶습니다. */
    public static NoShowCandidateWindow noShowCandidateWindow(LocalDateTime now) {
        Objects.requireNonNull(now, "now");
        return new NoShowCandidateWindow(readyEarliestStartsAt(now), now);
    }

    /**
     * 퇴근 스캔 후보의 {@code ends_at} 하한입니다.
     *
     * <p>{@code attemptedAt < checkOutMissingAt(ends_at)}을 옮긴 값입니다.</p>
     */
    public static LocalDateTime checkOutEarliestEndsAt(LocalDateTime attemptedAt) {
        return attemptedAt.minusHours(CHECK_OUT_GRACE_HOURS);
    }

    /**
     * 측정 시각이 승인된 신선도 범위 안인지 판정합니다.
     *
     * <p>서버 수신 시각보다 정확히 5분 전부터 1분 후까지 포함합니다. 뒤쪽 여유는 단말
     * 시계가 서버보다 조금 빠른 경우를 받아주기 위한 것입니다.</p>
     */
    public static boolean isCaptureFresh(Instant capturedAt, Instant receivedAt) {
        return capturedAt != null
                && !capturedAt.isBefore(receivedAt.minus(CAPTURE_MAX_AGE))
                && !capturedAt.isAfter(receivedAt.plus(CAPTURE_MAX_SKEW));
    }

    /** NO_SHOW 후보 SQL에 전달하는 두 경계를 한 타입으로 고정합니다. */
    public record NoShowCandidateWindow(
            LocalDateTime latestStartsAt,
            LocalDateTime latestEndsAt) {

        public NoShowCandidateWindow {
            Objects.requireNonNull(latestStartsAt, "latestStartsAt");
            Objects.requireNonNull(latestEndsAt, "latestEndsAt");
        }
    }
}
