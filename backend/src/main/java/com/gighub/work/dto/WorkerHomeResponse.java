package com.gighub.work.dto;

import java.time.Instant;
import java.time.LocalDateTime;

import com.gighub.common.api.ApiTimes;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.domain.AttendanceLateness;
import com.gighub.work.domain.ExpectedNetAmount;

import lombok.Getter;

/**
 * {@code GET /api/worker/home} 응답입니다.
 *
 * <p>오늘 근무 후보가 없으면 {@link #todayWorkCase}가 {@code null}입니다. {@code hourlyWage},
 * {@code expectedDeductionAmount}, {@code expectedPaymentAmount}는 {@code hourly_wage}가
 * 저장되지 않아(WORK-008 재구현 전) 이 응답에 포함하지 않습니다. 담당 기능 이슈가 없는 Blocked
 * 항목입니다({@code SPEC_TRACEABILITY.md} 5B-3·5B-4).</p>
 */
@Getter
public final class WorkerHomeResponse {

    private final TodayWorkCase todayWorkCase;

    private WorkerHomeResponse(TodayWorkCase todayWorkCase) {
        this.todayWorkCase = todayWorkCase;
    }

    public static WorkerHomeResponse empty() {
        return new WorkerHomeResponse(null);
    }

    public static WorkerHomeResponse of(
            Long workCaseId,
            String title,
            String workplaceName,
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            Integer breakMinutes,
            Boolean breakPaid,
            Long dailyWage,
            WorkCaseStatus status,
            LocalDateTime checkedInAt,
            LocalDateTime checkInAttemptedAt,
            LocalDateTime checkedOutAt,
            String escrowStatus,
            String settlementStatus,
            LocalDateTime settlementDueAt) {
        return new WorkerHomeResponse(new TodayWorkCase(
                workCaseId,
                title,
                workplaceName,
                startsAt,
                endsAt,
                breakMinutes,
                breakPaid,
                dailyWage,
                status,
                checkedInAt,
                checkInAttemptedAt,
                checkedOutAt,
                escrowStatus,
                settlementStatus,
                settlementDueAt));
    }

    @Getter
    public static final class TodayWorkCase {

        private final Long workCaseId;
        private final String title;
        private final String workplaceName;
        private final Instant startsAt;
        private final Instant endsAt;
        private final Integer breakMinutes;
        private final Boolean breakPaid;
        private final Long dailyWage;
        private final Long expectedNetAmount;
        private final WorkCaseStatus status;
        private final Attendance attendance;
        private final String escrowStatus;
        private final String settlementStatus;
        private final Instant settlementDueAt;

        private TodayWorkCase(
                Long workCaseId,
                String title,
                String workplaceName,
                LocalDateTime startsAt,
                LocalDateTime endsAt,
                Integer breakMinutes,
                Boolean breakPaid,
                Long dailyWage,
                WorkCaseStatus status,
                LocalDateTime checkedInAt,
                LocalDateTime checkInAttemptedAt,
                LocalDateTime checkedOutAt,
                String escrowStatus,
                String settlementStatus,
                LocalDateTime settlementDueAt) {
            this.workCaseId = workCaseId;
            this.title = title;
            this.workplaceName = workplaceName;
            this.startsAt = ApiTimes.toInstant(startsAt);
            this.endsAt = ApiTimes.toInstant(endsAt);
            this.breakMinutes = breakMinutes;
            this.breakPaid = breakPaid;
            this.dailyWage = dailyWage;
            this.expectedNetAmount = ExpectedNetAmount.calculate(dailyWage);
            this.status = status;
            this.attendance = Attendance.from(
                    startsAt, checkedInAt, checkInAttemptedAt, checkedOutAt);
            this.escrowStatus = escrowStatus;
            this.settlementStatus = settlementStatus;
            this.settlementDueAt = ApiTimes.toInstant(settlementDueAt);
        }
    }

    /**
     * 근태 파생값입니다.
     *
     * <p>{@code isLate}는 {@code boolean}이 아니라 {@link Boolean}이고 Getter 이름이
     * {@code getIsLate}입니다. {@code boolean isLate}로 두면 Lombok이 {@code isLate()}를
     * 만들고 Jackson이 접두사 {@code is}를 떼어 {@code "late"}로 직렬화해, API_SPEC이 고정한
     * {@code isLate} 필드 이름이 조용히 어긋납니다.
     * {@link com.gighub.attendance.dto.AttendanceScanResponse}도 같은 이유로 {@link Boolean}을
     * 씁니다.</p>
     */
    @Getter
    public static final class Attendance {

        private final Instant checkedInAt;
        private final Instant checkedOutAt;
        private final Boolean isLate;
        private final Integer lateMinutes;

        private Attendance(Instant checkedInAt, Instant checkedOutAt, AttendanceLateness lateness) {
            this.checkedInAt = checkedInAt;
            this.checkedOutAt = checkedOutAt;
            this.isLate = lateness.isLate();
            this.lateMinutes = lateness.isLate() ? lateness.getLateMinutes() : null;
        }

        private static Attendance from(
                LocalDateTime startsAt,
                LocalDateTime checkedInAt,
                LocalDateTime checkInAttemptedAt,
                LocalDateTime checkedOutAt) {
            AttendanceLateness lateness = AttendanceLateness.from(startsAt, checkInAttemptedAt);
            return new Attendance(
                    ApiTimes.toInstant(checkedInAt),
                    ApiTimes.toInstant(checkedOutAt),
                    lateness);
        }

        /** {@code work-cases} 목록 Item도 같은 근태 파생값 모양을 쓰기 위한 공개 팩토리입니다. */
        public static Attendance of(Instant checkedInAt, Instant checkedOutAt, AttendanceLateness lateness) {
            return new Attendance(checkedInAt, checkedOutAt, lateness);
        }
    }
}
