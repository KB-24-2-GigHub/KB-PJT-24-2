package com.gighub.workplace.geocoding;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Kakao Local 주소 검색 응답에서 좌표 확정에 필요한 부분만 읽습니다.
 *
 * <p>외부 응답은 우리 계약이 아니므로 필요한 필드만 남기고 나머지는 무시합니다. 전체를 그대로
 * 옮기면 외부 스키마 변경이 도메인까지 전파됩니다.</p>
 *
 * @param documents 주소 후보 목록
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoAddressSearchResponse(List<Document> documents) {

    /**
     * 주소 후보 하나의 좌표입니다.
     *
     * <p>Kakao는 경도를 {@code x}, 위도를 {@code y}로 돌려줍니다. 축 이름이 통념과 반대라
     * 여기서 한 번만 우리 이름으로 옮깁니다.</p>
     *
     * @param longitude 경도(외부 {@code x})
     * @param latitude  위도(외부 {@code y})
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Document(
            @JsonProperty("x") String longitude,
            @JsonProperty("y") String latitude) {
    }
}
