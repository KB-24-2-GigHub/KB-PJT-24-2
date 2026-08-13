package com.gighub.workplace.geocoding;

import com.gighub.workplace.exception.WorkplaceGeocodingException;

/**
 * 도로명주소를 사업장 좌표로 확정하는 경계입니다.
 *
 * <p>구현은 외부 주소 변환 서비스를 호출하지만 호출자는 그 사실을 알지 않습니다. 실패는
 * 반환값이 아니라 {@link WorkplaceGeocodingException}으로 알리므로, 좌표 없는 결과를 그대로
 * 저장 경로로 흘려보낼 수 없습니다.</p>
 */
public interface AddressGeocoder {

    /**
     * 도로명주소를 좌표로 확정합니다.
     *
     * <p>후보가 여러 건일 때 임의로 하나를 고르지 않습니다. 잘못 고른 좌표는 그대로 출퇴근
     * 반경 판정의 기준점이 되므로 확정 실패로 처리합니다.</p>
     *
     * @param roadAddress 정규화된 도로명주소
     * @return 확정된 좌표
     * @throws WorkplaceGeocodingException 주소를 확정할 수 없거나 외부 서비스가 응답하지 못한 경우
     */
    GeocodedCoordinates geocode(String roadAddress);
}
