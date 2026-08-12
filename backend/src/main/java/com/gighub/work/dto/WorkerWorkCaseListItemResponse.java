package com.gighub.work.dto;

import java.time.Instant;
import java.time.LocalDateTime;

import com.gighub.common.api.ApiTimes;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.domain.AttendanceLateness;

import lombok.Getter;

/**
 * {@code GET /api/worker/work-cases}의 Page Item 하나입니다.
 *
 * <p>저장 상태를 화면 별칭으로 바꾸지 않고 {@code CHECK_OUT_MISSING}을 {@code NO_SHOW}와
 * 그대로 구분합니다. 정밀 좌표, QR Token, OWNER 잔액과 계약 Storage 정보는 반환하지
 * 않습니다.</p>
 */
@Getter
public final class WorkerWorkCaseListItemResponse {

    private final Long workCaseId;
    private final String title;
    private final String workplaceName;
    private final Instant startsAt;
    private final Instant endsAt;
    private final Integer breakMinutes;
    private final Boolean breakPaid;
    private final Long dailyWage;
    private final WorkCaseStatus status;
    private final WorkerHomeResponse.Attendance attendance;
    private final String escrowStatus;
    private final String settlementStatus;
    private final Instant settlementDueAt;

    private WorkerWorkCaseListItemResponse(
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
        this.status = status;
        AttendanceLateness lateness = AttendanceLateness.from(startsAt, checkInAttemptedAt);
        this.attendance = WorkerHomeResponse.Attendance.of(
                ApiTimes.toInstant(checkedInAt),
                ApiTimes.toInstant(checkedOutAt),
                lateness);
        this.escrowStatus = escrowStatus;
        this.settlementStatus = settlementStatus;
        this.settlementDueAt = ApiTimes.toInstant(settlementDueAt);
    }

    public static WorkerWorkCaseListItemResponse of(
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
        return new WorkerWorkCaseListItemResponse(
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
                settlementDueAt);
    }
}
