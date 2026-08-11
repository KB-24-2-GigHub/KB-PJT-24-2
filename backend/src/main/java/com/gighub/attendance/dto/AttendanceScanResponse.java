package com.gighub.attendance.dto;

import java.time.Instant;

import com.gighub.attendance.domain.AttendanceType;

import lombok.Getter;

/** WORKER QR 출퇴근 스캔 판정 결과입니다. */
@Getter
public final class AttendanceScanResponse {

    private final String result;
    private final Long workCaseId;
    private final AttendanceType scanType;
    private final Instant recordedAt;
    private final Instant earlyCheckoutConfirmedAt;
    private final Instant scheduledEndAt;

    private AttendanceScanResponse(
            String result,
            Long workCaseId,
            AttendanceType scanType,
            Instant recordedAt,
            Instant earlyCheckoutConfirmedAt,
            Instant scheduledEndAt) {
        this.result = result;
        this.workCaseId = workCaseId;
        this.scanType = scanType;
        this.recordedAt = recordedAt;
        this.earlyCheckoutConfirmedAt = earlyCheckoutConfirmedAt;
        this.scheduledEndAt = scheduledEndAt;
    }

    public static AttendanceScanResponse recorded(
            Long workCaseId,
            AttendanceType scanType,
            Instant recordedAt,
            Instant earlyCheckoutConfirmedAt) {
        return new AttendanceScanResponse(
                "RECORDED", workCaseId, scanType, recordedAt, earlyCheckoutConfirmedAt, null);
    }

    public static AttendanceScanResponse confirmationRequired(
            Long workCaseId, Instant scheduledEndAt) {
        return new AttendanceScanResponse(
                "CONFIRMATION_REQUIRED",
                workCaseId,
                AttendanceType.CHECK_OUT,
                null,
                null,
                scheduledEndAt);
    }
}
