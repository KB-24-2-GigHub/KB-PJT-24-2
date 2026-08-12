package com.gighub.attendance.dto;

import java.math.BigDecimal;
import java.time.Instant;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;

/**
 * WORKER QR 출퇴근 스캔 입력입니다.
 *
 * <p>API_SPEC 6.0.0이 Body를 {@code qrToken}, {@code latitude}, {@code longitude},
 * {@code accuracyMeters}, {@code capturedAt}, {@code confirmEarlyCheckout} 6개로 고정했습니다.
 * 사용자·근무·사업장 식별자와 근태 유형, 상태는 서버가 정하므로 입력 필드로 두지 않고,
 * 명세에 없는 필드는 {@link #rejectUnknownField}가 요청 오류로 거절합니다. 조용히 무시하면
 * 클라이언트는 값이 반영된 줄 압니다.</p>
 *
 * <p>좌표와 정확도를 {@code BigDecimal}로 받는 이유는 소수 자릿수 자체가 계약이기 때문입니다.
 * {@code double}로 받으면 {@code @Digits}가 판정할 원래 자릿수가 사라집니다. 측정 시각의
 * 신선도는 서버 수신 시각과 비교해야 하므로 여기서 검증하지 않습니다.</p>
 */
@Getter
public final class AttendanceScanRequest {

    @NotBlank(message = "QR 코드 값은 필수입니다.")
    private final String qrToken;

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

    @NotNull(message = "조기 퇴근 확인 여부는 필수입니다.")
    private final Boolean confirmEarlyCheckout;

    @JsonCreator
    public AttendanceScanRequest(
            @JsonProperty("qrToken") String qrToken,
            @JsonProperty("latitude") BigDecimal latitude,
            @JsonProperty("longitude") BigDecimal longitude,
            @JsonProperty("accuracyMeters") BigDecimal accuracyMeters,
            @JsonProperty("capturedAt") Instant capturedAt,
            @JsonProperty("confirmEarlyCheckout") Boolean confirmEarlyCheckout) {
        this.qrToken = qrToken;
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracyMeters = accuracyMeters;
        this.capturedAt = capturedAt;
        this.confirmEarlyCheckout = confirmEarlyCheckout;
    }

    /** 명세에 없는 스캔 필드는 조용히 무시하지 않고 요청 오류로 처리합니다. */
    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("허용되지 않은 스캔 요청 필드입니다: " + fieldName);
    }

    public boolean isEarlyCheckoutConfirmed() {
        return Boolean.TRUE.equals(confirmEarlyCheckout);
    }
}
