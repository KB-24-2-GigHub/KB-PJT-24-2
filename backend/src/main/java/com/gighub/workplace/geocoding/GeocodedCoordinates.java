package com.gighub.workplace.geocoding;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 도로명주소 변환으로 확정된 사업장 좌표입니다.
 *
 * <p>SPEC-343-01은 좌표의 유일한 출처를 서버 주소 변환으로 고정합니다. 부분 좌표는 저장할 수
 * 없으므로 두 값이 함께 있을 때만 존재할 수 있는 값으로 둡니다.</p>
 *
 * @param latitude  위도
 * @param longitude 경도
 */
public record GeocodedCoordinates(BigDecimal latitude, BigDecimal longitude) {

    public GeocodedCoordinates {
        Objects.requireNonNull(latitude, "latitude");
        Objects.requireNonNull(longitude, "longitude");
    }
}
