package com.gighub.workplace.geocoding;

import com.gighub.workplace.exception.WorkplaceGeocodingException;

import org.springframework.stereotype.Component;

/**
 * 외부 주소 변환 연동이 아직 구성되지 않은 상태를 나타내는 임시 구현입니다.
 *
 * <p>Kakao Local REST API 키가 개발·배포 환경에 전달되기 전까지 이 Bean이 유일한
 * {@link AddressGeocoder}입니다. 좌표를 지어내지 않고 일시 실패로 끝냅니다. SPEC-343-01이
 * 좌표 없는 사업장을 저장하지 않기로 정했으므로, 이 구간에서는 사업장 등록이 성공하지 않는
 * 것이 의도된 동작입니다.</p>
 *
 * <p>고정 좌표를 반환하는 편법을 쓰지 않는 이유는 그 값이 그대로 출퇴근 반경 판정의 기준점이
 * 되기 때문입니다. 잘못된 기준점은 실패보다 늦게 발견됩니다.</p>
 *
 * <p>실제 연동 구현이 들어오면 이 Bean을 대체합니다.</p>
 */
@Component
public class UnconfiguredAddressGeocoder implements AddressGeocoder {

    @Override
    public GeocodedCoordinates geocode(String roadAddress) {
        throw WorkplaceGeocodingException.temporarilyUnavailable();
    }
}
