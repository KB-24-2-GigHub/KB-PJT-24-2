package com.gighub.attendance.domain;

import java.time.LocalDateTime;

/**
 * 근태 판정과 QR 스캔이 함께 쓰는 시간 경계입니다.
 *
 * <p>세 경계는 자동 판정 Scheduler와 스캔이 같은 값을 봐야 합니다. 값이 어긋나면 스캔은
 * 받아주는데 Scheduler가 NO_SHOW로 끝내거나, 반대로 Scheduler가 아직 READY로 만들지 않은
 * 근무를 스캔이 먼저 처리하는 모순이 생깁니다.</p>
 *
 * <ul>
 *   <li>{@code READY_LEAD} 시작 전 준비 전이와 출근 스캔이 열리는 시점</li>
 *   <li>{@code NO_SHOW_GRACE} 성공 출근이 없으면 결근으로 끝나는 시점</li>
 *   <li>{@code CHECK_OUT_GRACE} 성공 퇴근이 없으면 퇴근 누락으로 끝나는 시점</li>
 * </ul>
 */
public final class AttendanceWindowPolicy {

    private static final int READY_LEAD_MINUTES = 30;
    private static final int NO_SHOW_GRACE_HOURS = 1;
    private static final int CHECK_OUT_GRACE_HOURS = 2;
    private static final int CHECK_IN_REPLAY_MINUTES = 10;

    private AttendanceWindowPolicy() {
    }

    /**
     * 방금 성공한 출근의 재시도로 볼 시간인지 판정합니다.
     *
     * <p>응답을 못 받은 client가 같은 출근 의도로 다시 스캔했을 때, 성공 출근 기록이 있다는
     * 이유만으로 퇴근으로 넘어가면 근무가 시작하자마자 끝납니다. 출근 직후 짧은 구간의
     * 재스캔은 퇴근 의도로 보지 않습니다.</p>
     *
     * <p>구간을 넓힐수록 느린 재시도까지 지켜주지만, 그만큼 출근 직후의 진짜 퇴근 스캔도
     * 재시도로 취급됩니다. 근무는 시간 단위이므로 출근 10분 안에 퇴근하는 정상 시나리오는
     * 없다고 보고 이 구간을 잡았습니다. 이 안에서 정말 퇴근해야 한다면 구간이 지난 뒤 다시
     * 스캔하면 됩니다.</p>
     *
     * <p>승인된 재시도 식별자가 요청 필드에 없어 서버 상태만으로 판정합니다. SPEC이 재시도
     * 계약을 확정하면 그 값으로 대체합니다.</p>
     */
    public static boolean isCheckInReplay(LocalDateTime checkedInAt, LocalDateTime now) {
        return checkedInAt != null
                && !now.isBefore(checkedInAt)
                && now.isBefore(checkedInAt.plusMinutes(CHECK_IN_REPLAY_MINUTES));
    }

    /** 출근 스캔과 {@code ACCEPTED -> READY} 전이가 열리는 시점입니다. */
    public static LocalDateTime readyOpensAt(LocalDateTime startsAt) {
        return startsAt.minusMinutes(READY_LEAD_MINUTES);
    }

    /** 성공 출근이 없으면 결근으로 확정되는 시점입니다. */
    public static LocalDateTime noShowAt(LocalDateTime startsAt) {
        return startsAt.plusHours(NO_SHOW_GRACE_HOURS);
    }

    /** 성공 퇴근이 없으면 퇴근 누락으로 확정되는 시점입니다. */
    public static LocalDateTime checkOutMissingAt(LocalDateTime endsAt) {
        return endsAt.plusHours(CHECK_OUT_GRACE_HOURS);
    }

    /**
     * 지금 스캔할 수 있는 근무가 만족해야 하는 {@code starts_at} 상한입니다.
     *
     * <p>{@code now >= readyOpensAt(starts_at)}를 {@code starts_at} 기준으로 바꾼 값이라
     * 컬럼에 함수를 씌우지 않아 인덱스를 그대로 탑니다.</p>
     */
    public static LocalDateTime latestScannableStartsAt(LocalDateTime now) {
        return now.plusMinutes(READY_LEAD_MINUTES);
    }

    /**
     * 아직 결근으로 확정되지 않은 근무가 만족해야 하는 {@code starts_at} 하한입니다.
     *
     * <p>{@code now >= noShowAt(starts_at)}를 {@code starts_at} 기준으로 바꾼 값입니다.</p>
     */
    public static LocalDateTime latestNoShowFreeStartsAt(LocalDateTime now) {
        return now.minusHours(NO_SHOW_GRACE_HOURS);
    }

    /**
     * 지금 스캔할 수 있는 근무가 만족해야 하는 {@code ends_at} 하한입니다.
     *
     * <p>퇴근 누락으로 확정된 뒤에는 스캔 대상이 아니므로 {@code now < checkOutMissingAt}을
     * {@code ends_at} 기준으로 바꿉니다.</p>
     */
    public static LocalDateTime earliestScannableEndsAt(LocalDateTime now) {
        return now.minusHours(CHECK_OUT_GRACE_HOURS);
    }
}
