package com.gighub.work.dto;

import java.time.Instant;

import com.gighub.common.api.ApiTimes;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.domain.AttendanceLateness;
import com.gighub.work.domain.ExpectedNetAmount;
import com.gighub.work.mapper.result.WorkerHomeCandidateRow;

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

    public static WorkerHomeResponse from(WorkerHomeCandidateRow row) {
        return new WorkerHomeResponse(row == null ? null : TodayWorkCase.from(row));
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

        private TodayWorkCase(WorkerHomeCandidateRow row) {
            this.workCaseId = row.getWorkCaseId();
            this.title = row.getTitle();
            this.workplaceName = row.getWorkplaceName();
            this.startsAt = ApiTimes.toInstant(row.getStartsAt());
            this.endsAt = ApiTimes.toInstant(row.getEndsAt());
            this.breakMinutes = row.getBreakMinutes();
            this.breakPaid = row.getBreakPaid();
            this.dailyWage = row.getDailyWage();
            this.expectedNetAmount = ExpectedNetAmount.calculate(row.getDailyWage());
            this.status = row.getStatus();
            this.attendance = Attendance.from(row);
            this.escrowStatus = row.getEscrowStatus();
            this.settlementStatus = row.getSettlementStatus();
            this.settlementDueAt = ApiTimes.toInstant(row.getSettlementDueAt());
        }

        private static TodayWorkCase from(WorkerHomeCandidateRow row) {
            return new TodayWorkCase(row);
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

        private static Attendance from(WorkerHomeCandidateRow row) {
            AttendanceLateness lateness = AttendanceLateness.from(row.getStartsAt(), row.getCheckInAttemptedAt());
            return new Attendance(
                    ApiTimes.toInstant(row.getCheckedInAt()),
                    ApiTimes.toInstant(row.getCheckedOutAt()),
                    lateness);
        }

        /** {@code work-cases} 목록 Item도 같은 근태 파생값 모양을 쓰기 위한 공개 팩토리입니다. */
        public static Attendance of(Instant checkedInAt, Instant checkedOutAt, AttendanceLateness lateness) {
            return new Attendance(checkedInAt, checkedOutAt, lateness);
        }
    }
}
