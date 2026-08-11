package com.gighub.attendance.dto;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

/**
 * WORKER QR 출퇴근 스캔 입력입니다.
 *
 * <p>승인 필드는 {@code qrToken}, {@code latitude}, {@code longitude},
 * {@code confirmEarlyCheckout} 4개뿐입니다. client가 보내는 사용자·근무·사업장 식별자와
 * 시각은 신뢰하지 않으므로 필드로 두지 않고, 명세에 없는 필드는
 * {@link #rejectUnknownField}가 요청 오류로 거절합니다.</p>
 */
@Getter
public final class AttendanceScanRequest {

    @NotBlank(message = "QR 코드 값은 필수입니다.")
    private final String qrToken;

    @NotNull(message = "위도는 필수입니다.")
    @DecimalMin(value = "-90", message = "위도 범위를 벗어났습니다.")
    @DecimalMax(value = "90", message = "위도 범위를 벗어났습니다.")
    private final Double latitude;

    @NotNull(message = "경도는 필수입니다.")
    @DecimalMin(value = "-180", message = "경도 범위를 벗어났습니다.")
    @DecimalMax(value = "180", message = "경도 범위를 벗어났습니다.")
    private final Double longitude;

    private final Boolean confirmEarlyCheckout;

    @JsonCreator
    public AttendanceScanRequest(
            @JsonProperty("qrToken") String qrToken,
            @JsonProperty("latitude") Double latitude,
            @JsonProperty("longitude") Double longitude,
            @JsonProperty("confirmEarlyCheckout") Boolean confirmEarlyCheckout) {
        this.qrToken = qrToken;
        this.latitude = latitude;
        this.longitude = longitude;
        this.confirmEarlyCheckout = confirmEarlyCheckout;
    }

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("허용되지 않은 스캔 요청 필드입니다: " + fieldName);
    }
}
