package com.gighub.attendance.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gighub.attendance.domain.AttendanceType;

import lombok.Getter;

/**
 * WORKER QR 출퇴근 스캔 판정 결과입니다.
 *
 * <p>API_SPEC 6.0.0은 일반 성공이 {@code earlyCheckoutConfirmedAt}과
 * {@code settlementDueAt}을 값이 {@code null}이어도 <b>항상</b> 반환하도록 정했습니다.
 * 그래서 {@code NON_NULL} 제외를 쓰지 않고 필드를 그대로 직렬화합니다. 대신 조기 퇴근 확인
 * 응답에도 성공 전용 필드가 {@code null}로 함께 나갑니다. 두 형태를 정확히 가르려면 응답
 * 타입을 둘로 나눠야 하는데, 계약이 요구하는 "항상 반환"을 지키는 쪽을 택했습니다.</p>
 *
 * <p>Replay는 저장한 Snapshot을 다시 이 타입으로 복원하므로 역직렬화도 가능해야 합니다.</p>
 */
@Getter
public final class AttendanceScanResponse {

    private final String result;
    private final Long workCaseId;
    private final AttendanceType scanType;
    private final Instant recordedAt;
    private final Boolean isLate;
    private final Integer lateMinutes;
    private final Instant earlyCheckoutConfirmedAt;
    private final Instant settlementDueAt;
    private final Instant scheduledEndAt;

    @JsonCreator
    AttendanceScanResponse(
            @JsonProperty("result") String result,
            @JsonProperty("workCaseId") Long workCaseId,
            @JsonProperty("scanType") AttendanceType scanType,
            @JsonProperty("recordedAt") Instant recordedAt,
            @JsonProperty("isLate") Boolean isLate,
            @JsonProperty("lateMinutes") Integer lateMinutes,
            @JsonProperty("earlyCheckoutConfirmedAt") Instant earlyCheckoutConfirmedAt,
            @JsonProperty("settlementDueAt") Instant settlementDueAt,
            @JsonProperty("scheduledEndAt") Instant scheduledEndAt) {
        this.result = result;
        this.workCaseId = workCaseId;
        this.scanType = scanType;
        this.recordedAt = recordedAt;
        this.isLate = isLate;
        this.lateMinutes = lateMinutes;
        this.earlyCheckoutConfirmedAt = earlyCheckoutConfirmedAt;
        this.settlementDueAt = settlementDueAt;
        this.scheduledEndAt = scheduledEndAt;
    }

    /**
     * 기록에 성공한 스캔입니다.
     *
     * <p>{@code isLate}와 {@code lateMinutes}는 CHECK_IN에서만 의미가 있고,
     * {@code settlementDueAt}은 CHECK_OUT에서만 채워집니다.</p>
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
                settlementDueAt,
                null);
    }

    /** 예정 종료 전 퇴근이라 사용자 확인을 되묻는 결과입니다. */
    public static AttendanceScanResponse confirmationRequired(
            Long workCaseId, Instant scheduledEndAt) {
        return new AttendanceScanResponse(
                "CONFIRMATION_REQUIRED",
                workCaseId,
                AttendanceType.CHECK_OUT,
                null,
                null,
                null,
                null,
                null,
                scheduledEndAt);
    }
}
