package com.gighub.work.service.result;

import com.gighub.work.domain.WorkCaseStatus;

import java.time.LocalDateTime;

/** Attendance lifecycle 명령에 공개하는 잠긴 Work 최소 Snapshot입니다. */
public record WorkLifecycleSnapshot(
        long workCaseId,
        WorkCaseStatus status,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        long agreedWage,
        int breakMinutes,
        boolean breakPaid) {
}
