package com.gighub.work.domain;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;

/**
 * 근무 조건 입력의 날짜와 시각을 DB 저장 값으로 결합합니다.
 *
 * <p>{@code work_cases.starts_at}과 {@code ends_at}은 시간대가 없는 {@code DATETIME(6)}이고,
 * 이 프로젝트는 그 자리에 {@code Asia/Seoul} 벽시계 값을 넣는 규약을 씁니다. JDBC URL의
 * {@code serverTimezone=Asia/Seoul}과 {@link com.gighub.common.api.ApiTimes}가 같은 전제를
 * 씁니다. 그래서 저장할 때는 시간대를 변환하지 않고 응답 경계에서만 UTC {@code Instant}로
 * 바꿉니다. 여기서 한 번 더 변환하면 같은 값이 두 번 밀립니다.</p>
 *
 * <p>SPEC-413-01이 야간 근무를 지원합니다. 종료 시각이 시작 시각보다 뒤가 아니면 다음 날로
 * 결합하며({@link #combineEnd}), 그 결과 {@code ends_at > starts_at}은 항상 성립합니다. 자정
 * 넘김을 허용하는 순간 "종료가 시작보다 이르다"는 오타 방어선이 사라지므로, 그 자리를
 * {@link #MAX_WORK_DURATION} 길이 상한이 대신합니다.</p>
 *
 * <p>{@code workDate}는 여전히 <b>근무가 시작하는 날</b>입니다. 자정을 넘겨도 그 근무는
 * 시작일의 근무이며, 목록 기간 조회가 {@code DATE(starts_at)}를 쓰므로 캘린더 배치도
 * 시작일을 따릅니다.</p>
 */
public final class WorkCaseTimes {

    /**
     * 한 건의 근무가 가질 수 있는 최대 길이입니다(SPEC-413-01).
     *
     * <p>야간 알바의 최장 근무를 덮으면서 {@code 09:00~08:00} 같은 오타를 걸러 내는 값입니다.
     * 상한이 없으면 그런 입력이 23시간 근무로 조용히 저장됩니다.</p>
     */
    public static final Duration MAX_WORK_DURATION = Duration.ofHours(16);

    private WorkCaseTimes() {
    }

    /**
     * 근무일과 시각을 DB에 저장할 {@code Asia/Seoul} 벽시계 값으로 결합합니다.
     *
     * @param workDate 근무일
     * @param time     같은 날짜에 결합할 시각
     * @return 시간대 변환 없이 결합한 {@code DATETIME} 저장 값
     */
    public static LocalDateTime combine(LocalDate workDate, LocalTime time) {
        Objects.requireNonNull(workDate, "workDate");
        Objects.requireNonNull(time, "time");
        return LocalDateTime.of(workDate, time);
    }

    /**
     * 근무일과 종료 시각을 결합합니다. 종료가 시작보다 뒤가 아니면 다음 날로 넘깁니다.
     *
     * <p>{@code 23:00~01:00}은 근무일 다음 날 {@code 01:00}이 되고, {@code 09:00~18:00}은
     * 같은 날 {@code 18:00} 그대로입니다. 시작과 종료가 같은 {@code 09:00~09:00}도 다음 날로
     * 보아 24시간이 되며, 이는 {@link #MAX_WORK_DURATION}에서 걸립니다 — 여기서 0분 근무로
     * 접으면 오타가 유효한 값이 되어 버립니다.</p>
     *
     * @param workDate  근무가 시작하는 날
     * @param startTime 시작 시각
     * @param endTime   종료 시각
     * @return 결합한 종료 시각. 반환값은 항상 {@code combine(workDate, startTime)}보다 뒤입니다.
     */
    public static LocalDateTime combineEnd(LocalDate workDate, LocalTime startTime, LocalTime endTime) {
        Objects.requireNonNull(workDate, "workDate");
        Objects.requireNonNull(startTime, "startTime");
        Objects.requireNonNull(endTime, "endTime");
        LocalDate endDate = endTime.isAfter(startTime) ? workDate : workDate.plusDays(1);
        return LocalDateTime.of(endDate, endTime);
    }

    /**
     * {@code ck_work_cases_time} CHECK와 같은 조건을 애플리케이션에서 먼저 확인합니다.
     *
     * <p>DB 제약은 최종 방어선으로 남겨 두되, 제약 위반 예외를 사용자 메시지로 되돌리는 것보다
     * 입력 단계에서 걸러 내는 편이 응답이 정확합니다.</p>
     *
     * <p>{@link #combineEnd}로 결합했다면 이 조건은 구조적으로 참입니다. 그래도 남겨 두는 것은
     * 결합 경로가 하나뿐이라는 보장이 코드에 없기 때문입니다.</p>
     *
     * @param startsAt 결합된 시작 시각
     * @param endsAt   결합된 종료 시각
     * @return 종료가 시작보다 뒤이면 {@code true}
     */
    public static boolean endsAfterStart(LocalDateTime startsAt, LocalDateTime endsAt) {
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(endsAt, "endsAt");
        return endsAt.isAfter(startsAt);
    }

    /**
     * 근무 길이가 {@link #MAX_WORK_DURATION} 이내인지 확인합니다. 정확히 상한값이면 허용합니다.
     *
     * @param startsAt 결합된 시작 시각
     * @param endsAt   결합된 종료 시각
     * @return 간격이 상한 이하이면 {@code true}
     */
    public static boolean withinMaxDuration(LocalDateTime startsAt, LocalDateTime endsAt) {
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(endsAt, "endsAt");
        return Duration.between(startsAt, endsAt).compareTo(MAX_WORK_DURATION) <= 0;
    }
}
