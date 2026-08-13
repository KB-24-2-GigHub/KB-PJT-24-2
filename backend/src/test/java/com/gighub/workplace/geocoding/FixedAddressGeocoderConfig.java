package com.gighub.workplace.geocoding;

import java.math.BigDecimal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * DB Test가 사업장을 만들 때 실제 외부 주소 변환을 호출하지 않도록 고정 좌표로 대체합니다.
 *
 * <p>사업장 생성은 이제 주소 변환을 거치므로, 이 대체가 없으면 Security·MyBatis·DB 제약을
 * 검증하려는 Test가 외부 서비스의 응답과 네트워크 상태에 따라 결과가 바뀝니다. 키가 없는
 * 환경에서는 아예 실행할 수 없게 됩니다.</p>
 *
 * <p>{@code RootConfig}와 함께 등록해 실제 Bean을 덮습니다.</p>
 */
@Configuration
public class FixedAddressGeocoderConfig {

    public static final BigDecimal LATITUDE = new BigDecimal("37.1234567");
    public static final BigDecimal LONGITUDE = new BigDecimal("127.1234567");

    @Bean
    @Primary
    public AddressGeocoder fixedAddressGeocoder() {
        return roadAddress -> new GeocodedCoordinates(LATITUDE, LONGITUDE);
    }
}
