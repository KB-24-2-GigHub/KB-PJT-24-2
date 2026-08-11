package com.gighub.attendance.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gighub.attendance.domain.AttendanceType;

import lombok.Getter;

/**
 * 기록에 성공한 WORKER QR 출퇴근 스캔 결과입니다.
 *
 * <p>API_SPEC 6.0.0은 {@code isLate}·{@code lateMinutes}·{@code earlyCheckoutConfirmedAt}·
 * {@code settlementDueAt}을 값이 {@code null}이어도 항상 반환하도록 정했습니다. 이 타입은
 * {@code RECORDED} 형태만 표현하므로 {@code scheduledEndAt} 같은
 * {@link AttendanceScanConfirmationResponse} 전용 필드를 갖지 않습니다.</p>
 *
 * <p>Replay는 저장한 Snapshot을 다시 이 타입으로 복원하므로 역직렬화도 가능해야 합니다.</p>
 */
@Getter
public final class AttendanceScanResponse implements AttendanceScanResult {

    private final String result;
    private final Long workCaseId;
    private final AttendanceType scanType;
    private final Instant recordedAt;
    private final Boolean isLate;
    private final Integer lateMinutes;
    private final Instant earlyCheckoutConfirmedAt;
    private final Instant settlementDueAt;

    @JsonCreator
    AttendanceScanResponse(
            @JsonProperty("result") String result,
            @JsonProperty("workCaseId") Long workCaseId,
            @JsonProperty("scanType") AttendanceType scanType,
            @JsonProperty("recordedAt") Instant recordedAt,
            @JsonProperty("isLate") Boolean isLate,
            @JsonProperty("lateMinutes") Integer lateMinutes,
            @JsonProperty("earlyCheckoutConfirmedAt") Instant earlyCheckoutConfirmedAt,
            @JsonProperty("settlementDueAt") Instant settlementDueAt) {
        this.result = result;
        this.workCaseId = workCaseId;
        this.scanType = scanType;
        this.recordedAt = recordedAt;
        this.isLate = isLate;
        this.lateMinutes = lateMinutes;
        this.earlyCheckoutConfirmedAt = earlyCheckoutConfirmedAt;
        this.settlementDueAt = settlementDueAt;
    }

    /**
     * {@code isLate}와 {@code lateMinutes}는 CHECK_IN에서만 의미가 있고,
     * {@code settlementDueAt}은 CHECK_OUT에서만 채워집니다.
     */
    public static AttendanceScanResponse recorded(
            Long workCaseId,
            AttendanceType scanType,
            Instant recordedAt,
            boolean late,
            Integer lateMinutes,
            Instant earlyCheckoutConfirmedAt,
            Instant settlementDueAt) {
        return new AttendanceScanResponse(
                "RECORDED",
                workCaseId,
                scanType,
                recordedAt,
                late,
                lateMinutes,
                earlyCheckoutConfirmedAt,
                settlementDueAt);
    }
}
