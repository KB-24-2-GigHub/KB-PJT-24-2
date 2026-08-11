package com.gighub.work.dto;

import java.time.Instant;

import com.gighub.common.api.ApiTimes;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.domain.AttendanceLateness;
import com.gighub.work.mapper.result.WorkerWorkCaseRow;

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

    private WorkerWorkCaseListItemResponse(WorkerWorkCaseRow row) {
        this.workCaseId = row.getWorkCaseId();
        this.title = row.getTitle();
        this.workplaceName = row.getWorkplaceName();
        this.startsAt = ApiTimes.toInstant(row.getStartsAt());
        this.endsAt = ApiTimes.toInstant(row.getEndsAt());
        this.breakMinutes = row.getBreakMinutes();
        this.breakPaid = row.getBreakPaid();
        this.dailyWage = row.getDailyWage();
        this.status = row.getStatus();
        AttendanceLateness lateness = AttendanceLateness.from(row.getStartsAt(), row.getCheckInAttemptedAt());
        this.attendance = WorkerHomeResponse.Attendance.of(
                ApiTimes.toInstant(row.getCheckedInAt()),
                ApiTimes.toInstant(row.getCheckedOutAt()),
                lateness);
        this.escrowStatus = row.getEscrowStatus();
        this.settlementStatus = row.getSettlementStatus();
        this.settlementDueAt = ApiTimes.toInstant(row.getSettlementDueAt());
    }

    public static WorkerWorkCaseListItemResponse from(WorkerWorkCaseRow row) {
        return new WorkerWorkCaseListItemResponse(row);
    }
}
