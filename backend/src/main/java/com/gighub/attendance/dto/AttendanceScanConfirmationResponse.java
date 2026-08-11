package com.gighub.attendance.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gighub.attendance.domain.AttendanceType;

import lombok.Getter;

/**
 * 예정 종료 전 CHECK_OUT이라 사용자 확인을 되묻는 결과입니다.
 *
 * <p>확인 전에는 성공·거절 근태 행도 상태 변경도 만들지 않으므로, 이 타입은
 * {@link AttendanceScanResponse}의 성공 전용 필드({@code recordedAt},
 * {@code isLate} 등)를 갖지 않습니다. {@code scanType}은 항상 {@code CHECK_OUT}입니다.</p>
 */
@Getter
public final class AttendanceScanConfirmationResponse implements AttendanceScanResult {

    private final String result;
    private final Long workCaseId;
    private final AttendanceType scanType;
    private final Instant scheduledEndAt;

    @JsonCreator
    AttendanceScanConfirmationResponse(
            @JsonProperty("result") String result,
            @JsonProperty("workCaseId") Long workCaseId,
            @JsonProperty("scanType") AttendanceType scanType,
            @JsonProperty("scheduledEndAt") Instant scheduledEndAt) {
        this.result = result;
        this.workCaseId = workCaseId;
        this.scanType = scanType;
        this.scheduledEndAt = scheduledEndAt;
    }

    public static AttendanceScanConfirmationResponse of(Long workCaseId, Instant scheduledEndAt) {
        return new AttendanceScanConfirmationResponse(
                "CONFIRMATION_REQUIRED", workCaseId, AttendanceType.CHECK_OUT, scheduledEndAt);
    }
}
