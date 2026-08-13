package com.gighub.workplace.dto;

import java.math.BigDecimal;
import java.time.Instant;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

/**
 * 사업장 현장 위치 확정 입력입니다(API_SPEC "사업장 출퇴근 위치 확정").
 *
 * <p>필드 구성과 검증은 {@code AttendanceScanRequest}와 의도적으로 같습니다. 좌표·정확도를
 * {@code BigDecimal}로 받는 이유도 같습니다 — 소수 자릿수 자체가 계약이라 {@code double}로
 * 받으면 {@code @Digits}가 판정할 원래 자릿수가 사라집니다. 측정 시각의 신선도는 서버 수신
 * 시각과 비교해야 하므로 여기서 검증하지 않습니다.</p>
 */
@Getter
public final class WorkplaceCoordinateConfirmRequest {

    @NotNull(message = "위도는 필수입니다.")
    @DecimalMin(value = "-90", message = "위도 범위를 벗어났습니다.")
    @DecimalMax(value = "90", message = "위도 범위를 벗어났습니다.")
    @Digits(integer = 3, fraction = 7, message = "위도는 소수점 7자리 이하여야 합니다.")
    private final BigDecimal latitude;

    @NotNull(message = "경도는 필수입니다.")
    @DecimalMin(value = "-180", message = "경도 범위를 벗어났습니다.")
    @DecimalMax(value = "180", message = "경도 범위를 벗어났습니다.")
    @Digits(integer = 3, fraction = 7, message = "경도는 소수점 7자리 이하여야 합니다.")
    private final BigDecimal longitude;

    @NotNull(message = "위치 정확도는 필수입니다.")
    @DecimalMin(value = "0", message = "위치 정확도는 0 이상이어야 합니다.")
    @DecimalMax(value = "100", message = "위치 정확도는 100 이하여야 합니다.")
    @Digits(integer = 3, fraction = 2, message = "위치 정확도는 소수점 2자리 이하여야 합니다.")
    private final BigDecimal accuracyMeters;

    @NotNull(message = "위치 측정 시각은 필수입니다.")
    private final Instant capturedAt;

    @JsonCreator
    public WorkplaceCoordinateConfirmRequest(
            @JsonProperty("latitude") BigDecimal latitude,
            @JsonProperty("longitude") BigDecimal longitude,
            @JsonProperty("accuracyMeters") BigDecimal accuracyMeters,
            @JsonProperty("capturedAt") Instant capturedAt) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracyMeters = accuracyMeters;
        this.capturedAt = capturedAt;
    }

    /** 명세에 없는 필드는 조용히 무시하지 않고 요청 오류로 처리합니다. */
    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("허용되지 않은 위치 확정 요청 필드입니다: " + fieldName);
    }
}
