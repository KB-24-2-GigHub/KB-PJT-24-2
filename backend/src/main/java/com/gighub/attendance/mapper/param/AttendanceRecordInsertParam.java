package com.gighub.attendance.mapper.param;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.gighub.attendance.domain.AttendanceResult;
import com.gighub.attendance.domain.AttendanceType;

import lombok.Builder;
import lombok.Getter;

/** 근태 시도 한 건을 저장하는 Mapper 입력입니다. */
@Getter
@Builder
public class AttendanceRecordInsertParam {

    private final Long workCaseId;
    private final Long workerId;
    private final Long qrTokenId;
    private final AttendanceType attendanceType;
    private final LocalDateTime capturedAt;
    private final LocalDateTime attemptedAt;
    private final BigDecimal distanceMeters;
    private final AttendanceResult result;
    private final String failureReason;
    private final LocalDateTime earlyCheckoutConfirmedAt;
}
