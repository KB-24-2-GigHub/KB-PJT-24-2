package com.gighub.workplace.geocoding;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import com.gighub.common.api.ApiErrorCode;
import com.gighub.workplace.exception.WorkplaceGeocodingException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 좌표 확정 판정만 검증합니다.
 *
 * <p>후보를 몇 건까지 좌표로 인정하는지가 이 연동의 핵심 규칙입니다. 잘못 고른 좌표는 그대로
 * 출퇴근 반경 판정의 기준점이 되고 실패보다 늦게 발견됩니다.</p>
 */
class KakaoLocalAddressGeocoderTest {

    private final KakaoLocalAddressGeocoder geocoder =
            new KakaoLocalAddressGeocoder(new MockEnvironment());

    /** Kakao는 경도를 x, 위도를 y로 돌려줍니다. 축이 뒤바뀌면 전혀 다른 지점이 됩니다. */
    @Test
    void mapsExternalAxesIntoLatitudeAndLongitude() {
        GeocodedCoordinates coordinates = geocoder.toSingleCoordinates(
                response(new KakaoAddressSearchResponse.Document("127.1234567", "37.1234567")));

        assertEquals(0, new BigDecimal("37.1234567").compareTo(coordinates.latitude()));
        assertEquals(0, new BigDecimal("127.1234567").compareTo(coordinates.longitude()));
    }

    @Test
    void rejectsAddressWithoutAnyCandidate() {
        assertEquals(
                ApiErrorCode.WORKPLACE_ADDRESS_NOT_RESOLVABLE,
                assertThrows(
                        WorkplaceGeocodingException.class,
                        () -> geocoder.toSingleCoordinates(response())).getCode());
    }

    /** 복수 후보에서 첫 결과를 고르지 않습니다. */
    @Test
    void rejectsAmbiguousAddressInsteadOfPickingFirstCandidate() {
        KakaoAddressSearchResponse ambiguous = response(
                new KakaoAddressSearchResponse.Document("127.1000000", "37.1000000"),
                new KakaoAddressSearchResponse.Document("127.2000000", "37.2000000"));

        assertEquals(
                ApiErrorCode.WORKPLACE_ADDRESS_NOT_RESOLVABLE,
                assertThrows(
                        WorkplaceGeocodingException.class,
                        () -> geocoder.toSingleCoordinates(ambiguous)).getCode());
    }

    /**
     * 2xx라도 본문·{@code documents}·좌표가 계약과 다르면 외부 응답 해석 실패입니다.
     *
     * <p>주소 오류(422)로 분류하면 사용자가 멀쩡한 주소를 계속 고치게 되고, 처리하지 않으면
     * 500이 나갑니다. 계약상 일시 실패 하나로 모읍니다.</p>
     */
    @Test
    void reportsTemporaryFailureWhenResponseCannotBeInterpreted() {
        assertEquals(
                ApiErrorCode.WORKPLACE_GEOCODING_TEMPORARILY_UNAVAILABLE,
                assertThrows(
                        WorkplaceGeocodingException.class,
                        () -> geocoder.toSingleCoordinates(null)).getCode(),
                "본문이 없는 2xx");

        assertEquals(
                ApiErrorCode.WORKPLACE_GEOCODING_TEMPORARILY_UNAVAILABLE,
                assertThrows(
                        WorkplaceGeocodingException.class,
                        () -> geocoder.toSingleCoordinates(
                                new KakaoAddressSearchResponse(null))).getCode(),
                "documents 자체가 없는 2xx");

        assertEquals(
                ApiErrorCode.WORKPLACE_GEOCODING_TEMPORARILY_UNAVAILABLE,
                assertThrows(
                        WorkplaceGeocodingException.class,
                        () -> geocoder.toSingleCoordinates(
                                singleton(new KakaoAddressSearchResponse.Document(null, null))))
                        .getCode(),
                "후보는 하나인데 좌표가 없음");

        assertEquals(
                ApiErrorCode.WORKPLACE_GEOCODING_TEMPORARILY_UNAVAILABLE,
                assertThrows(
                        WorkplaceGeocodingException.class,
                        () -> geocoder.toSingleCoordinates(
                                singleton(new KakaoAddressSearchResponse.Document("x", "y"))))
                        .getCode(),
                "좌표가 숫자가 아님");

        assertEquals(
                ApiErrorCode.WORKPLACE_GEOCODING_TEMPORARILY_UNAVAILABLE,
                assertThrows(
                        WorkplaceGeocodingException.class,
                        () -> geocoder.toSingleCoordinates(
                                new KakaoAddressSearchResponse(singletonList(null)))).getCode(),
                "후보 자리가 비어 있음");
    }

    /** 키가 없는 환경은 좌표를 지어내지 않고 일시 실패로 끝납니다. */
    @Test
    void reportsTemporaryFailureWhenKeyIsMissing() {
        assertEquals(
                ApiErrorCode.WORKPLACE_GEOCODING_TEMPORARILY_UNAVAILABLE,
                assertThrows(
                        WorkplaceGeocodingException.class,
                        () -> geocoder.geocode("서울 강남구 테헤란로 1")).getCode());
    }

    private KakaoAddressSearchResponse response(KakaoAddressSearchResponse.Document... documents) {
        return new KakaoAddressSearchResponse(List.of(documents));
    }

    private KakaoAddressSearchResponse singleton(KakaoAddressSearchResponse.Document document) {
        return response(document);
    }

    private static List<KakaoAddressSearchResponse.Document> singletonList(
            KakaoAddressSearchResponse.Document document) {
        return Collections.singletonList(document);
    }
}
