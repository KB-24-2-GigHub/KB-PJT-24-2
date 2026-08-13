package com.gighub.workplace.geocoding;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.gighub.workplace.exception.WorkplaceGeocodingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Kakao Local 주소 검색으로 도로명주소를 좌표로 확정합니다(SPEC-343-01).
 *
 * <p>키는 저장소에 두지 않고 {@code DatabaseConfig}가 읽어 들이는 외부 properties에서
 * 주입합니다. 키가 없는 환경에서도 애플리케이션은 기동해야 하므로 생성 시점이 아니라 호출
 * 시점에 일시 실패로 끝냅니다. 기동을 막으면 사업장 등록과 무관한 기능까지 함께 죽습니다.</p>
 *
 * <p>외부 응답의 상태 코드와 본문은 밖으로 옮기지 않습니다. 그대로 노출하면 연동 구성과 키
 * 상태가 드러납니다.</p>
 */
@Component
public class KakaoLocalAddressGeocoder implements AddressGeocoder {

    private static final Logger log = LoggerFactory.getLogger(KakaoLocalAddressGeocoder.class);

    private static final String SEARCH_URL = "https://dapi.kakao.com/v2/local/search/address.json";
    private static final String KEY_PROPERTY = "kakao.local.rest-api-key";
    private static final String AUTHORIZATION_PREFIX = "KakaoAK ";

    /** 외부 호출이 DB 커넥션과 요청 Thread를 오래 붙잡지 않도록 상한을 명시합니다. */
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int READ_TIMEOUT_MS = 3_000;

    private final RestTemplate restTemplate;
    private final String restApiKey;

    public KakaoLocalAddressGeocoder(Environment environment) {
        this.restApiKey = environment.getProperty(KEY_PROPERTY);

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
        this.restTemplate = new RestTemplate(requestFactory);
    }

    @Override
    public GeocodedCoordinates geocode(String roadAddress) {
        if (!StringUtils.hasText(restApiKey)) {
            // 연동이 구성되지 않은 환경입니다. 좌표를 지어내지 않고 일시 실패로 끝냅니다.
            // 사용자에게는 일시 실패로 보이지만 실제로는 배포 구성 누락이므로 구분해 남깁니다.
            log.warn("사업장 주소 변환 키가 구성되지 않았습니다. property={}", KEY_PROPERTY);
            throw WorkplaceGeocodingException.temporarilyUnavailable();
        }
        if (!StringUtils.hasText(roadAddress)) {
            throw WorkplaceGeocodingException.addressNotResolvable();
        }

        KakaoAddressSearchResponse response = search(roadAddress);
        return toSingleCoordinates(response);
    }

    /** 외부 호출 실패는 원인을 구분하지 않고 모두 일시 실패입니다. 재시도로 풀릴 수 있습니다. */
    private KakaoAddressSearchResponse search(String roadAddress) {
        URI uri = UriComponentsBuilder.fromUriString(SEARCH_URL)
                .queryParam("query", roadAddress)
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, AUTHORIZATION_PREFIX + restApiKey);

        try {
            ResponseEntity<KakaoAddressSearchResponse> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers),
                    KakaoAddressSearchResponse.class);
            return response.getBody();
        } catch (HttpStatusCodeException exception) {
            // 외부 상태와 본문은 사용자 응답이 아니라 서버 로그에만 남깁니다. 키 미승인이나
            // 서비스 비활성 같은 구성 오류는 이 본문에만 드러나고, 사용자에게 보이는 일시
            // 실패 문구만으로는 원인을 알 수 없습니다. Authorization Header는 남기지 않습니다.
            log.warn("사업장 주소 변환 외부 응답이 실패했습니다. status={}, body={}",
                    exception.getStatusCode().value(), exception.getResponseBodyAsString());
            throw WorkplaceGeocodingException.temporarilyUnavailable();
        } catch (RestClientException exception) {
            // Timeout과 본문 해석 실패가 여기로 옵니다. 사용자 입력 문제가 아니므로 주소
            // 오류로 바꾸지 않습니다.
            log.warn("사업장 주소 변환 외부 호출에 실패했습니다.", exception);
            throw WorkplaceGeocodingException.temporarilyUnavailable();
        }
    }

    /**
     * 후보가 정확히 하나일 때만 좌표를 확정합니다.
     *
     * <p>0건은 주소를 확인할 수 없는 경우이고, 복수는 어느 위치인지 정할 수 없는 경우입니다.
     * 둘 다 사용자가 주소를 고쳐야 하는 확정 실패입니다. 첫 결과를 고르면 잘못된 기준점이
     * 조용히 저장돼 출퇴근 반경 판정을 계속 어긋나게 합니다.</p>
     *
     * <p>본문 자체가 없거나 {@code documents}가 아예 없는 응답은 다릅니다. 사용자가 보낸
     * 주소와 무관한 외부 응답 해석 실패이므로 일시 실패로 분류합니다. 주소 오류로 바꾸면
     * 사용자가 멀쩡한 주소를 계속 고치게 됩니다.</p>
     *
     * <p>실제 HTTP 호출 없이 이 판정만 검증할 수 있도록 같은 Package에 열어 둡니다.</p>
     */
    GeocodedCoordinates toSingleCoordinates(KakaoAddressSearchResponse response) {
        if (response == null || response.documents() == null) {
            log.warn("사업장 주소 변환 응답을 해석할 수 없습니다. body={}",
                    response == null ? "none" : "documents 없음");
            throw WorkplaceGeocodingException.temporarilyUnavailable();
        }

        List<KakaoAddressSearchResponse.Document> documents = response.documents();
        if (documents.size() != 1) {
            // 후보 수만 남깁니다. 확정 실패가 0건 때문인지 복수 때문인지 구분됩니다.
            log.info("사업장 주소를 좌표로 확정하지 못했습니다. candidates={}", documents.size());
            throw WorkplaceGeocodingException.addressNotResolvable();
        }

        KakaoAddressSearchResponse.Document document = documents.get(0);
        if (document == null) {
            log.warn("사업장 주소 변환 응답의 후보가 비어 있습니다.");
            throw WorkplaceGeocodingException.temporarilyUnavailable();
        }
        return new GeocodedCoordinates(
                toCoordinate(document.latitude()), toCoordinate(document.longitude()));
    }

    /**
     * 좌표는 문자열로 오므로 저장 전에 숫자로 확정합니다.
     *
     * <p>후보를 하나로 특정한 뒤 좌표가 비었거나 숫자가 아니라면 주소 문제가 아니라 외부
     * 응답이 계약과 다른 것입니다. 일시 실패로 분류해 사용자가 주소를 고치게 만들지 않습니다.</p>
     */
    private BigDecimal toCoordinate(String value) {
        if (!StringUtils.hasText(value)) {
            log.warn("사업장 주소 변환 응답에 좌표가 없습니다.");
            throw WorkplaceGeocodingException.temporarilyUnavailable();
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException exception) {
            log.warn("사업장 주소 변환 응답의 좌표를 숫자로 해석할 수 없습니다.");
            throw WorkplaceGeocodingException.temporarilyUnavailable();
        }
    }
}
