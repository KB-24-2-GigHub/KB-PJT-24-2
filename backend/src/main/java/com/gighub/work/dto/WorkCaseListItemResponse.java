package com.gighub.work.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.gighub.common.api.ApiTimes;
import com.gighub.work.domain.WorkCaseStatus;

import lombok.Getter;

/**
 * 근무 목록의 Item 하나입니다.
 *
 * <p>API_SPEC 4.0.0이 고정한 닫힌 필드 집합만 두고, 미매칭 근무는 {@code worker} 필드 내부를
 * nullable로 만들지 않고 객체 전체를 {@code null}로 반환합니다.</p>
 */
@Getter
public final class WorkCaseListItemResponse {

    private final Long workCaseId;
    private final String title;
    private final LocalDate workDate;
    private final Instant startsAt;
    private final Instant endsAt;
    private final Long dailyWage;
    private final WorkCaseStatus status;
    private final WorkerSummary worker;

    private WorkCaseListItemResponse(
            Long workCaseId,
            String title,
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            Long dailyWage,
            WorkCaseStatus status,
            Long workerId,
            String workerName) {
        this.workCaseId = workCaseId;
        this.title = title;
        // workDate는 저장 컬럼이 아니라 startsAt에서 파생합니다(API_SPEC 4.0.0).
        this.workDate = startsAt.toLocalDate();
        this.startsAt = ApiTimes.toInstant(startsAt);
        this.endsAt = ApiTimes.toInstant(endsAt);
        this.dailyWage = dailyWage;
        this.status = status;
        this.worker = workerId == null
                ? null
                : new WorkerSummary(workerId, workerName);
    }

    public static WorkCaseListItemResponse of(
            Long workCaseId,
            String title,
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            Long dailyWage,
            WorkCaseStatus status,
            Long workerId,
            String workerName) {
        return new WorkCaseListItemResponse(
                workCaseId, title, startsAt, endsAt, dailyWage, status, workerId, workerName);
    }

    @Getter
    public static final class WorkerSummary {

        private final Long workerId;
        private final String name;

        private WorkerSummary(Long workerId, String name) {
            this.workerId = workerId;
            this.name = name;
        }
    }
}
