package com.gighub.workplace.dto;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Builder 역직렬화 배선을 고정합니다. {@code @JsonPOJOBuilder(withPrefix = "")}가 빠지면
 * 검증 테스트는 그대로 통과하면서 실제 요청 필드만 조용히 비므로 별도로 확인합니다.
 */
class WorkplaceCreateRequestJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void bindsApprovedBodyThroughBuilder() throws JsonProcessingException {
        String body = "{"
                + "\"businessRegistrationNumber\":\"1234567890\","
                + "\"name\":\"  강남점  \","
                + "\"representativeName\":\"김사장\","
                + "\"roadAddress\":\"서울 강남구 테헤란로 1\","
                + "\"detailAddress\":\"2층\","
                + "\"phone\":\"02-1234-5678\""
                + "}";

        WorkplaceCreateRequest request = objectMapper.readValue(body, WorkplaceCreateRequest.class);

        assertEquals("1234567890", request.getBusinessRegistrationNumber());
        assertEquals("강남점", request.getName());
        assertEquals("김사장", request.getRepresentativeName());
        assertEquals("서울 강남구 테헤란로 1", request.getRoadAddress());
        assertEquals("2층", request.getDetailAddress());
        assertEquals("0212345678", request.getPhone());
    }

    @Test
    void bindsBodyWithoutOptionalFields() throws JsonProcessingException {
        String body = "{"
                + "\"businessRegistrationNumber\":\"1234567890\","
                + "\"name\":\"강남점\","
                + "\"representativeName\":\"김사장\","
                + "\"roadAddress\":\"서울 강남구 테헤란로 1\","
                + "\"phone\":\"0212345678\""
                + "}";

        WorkplaceCreateRequest request = objectMapper.readValue(body, WorkplaceCreateRequest.class);

        assertNull(request.getDetailAddress());
    }

    /** 좌표는 서버가 주소로 확정하므로 Body가 실어 보내면 거절합니다(SPEC-343-01). */
    @Test
    void rejectsBodyCarryingClientSuppliedCoordinates() {
        String body = "{"
                + "\"businessRegistrationNumber\":\"1234567890\","
                + "\"name\":\"강남점\","
                + "\"representativeName\":\"김사장\","
                + "\"roadAddress\":\"서울 강남구 테헤란로 1\","
                + "\"phone\":\"0212345678\","
                + "\"latitude\":37.1234567,"
                + "\"longitude\":127.1234567"
                + "}";

        assertThrows(
                JsonProcessingException.class,
                () -> objectMapper.readValue(body, WorkplaceCreateRequest.class));
    }

    @Test
    void rejectsBodyCarryingUnapprovedRadius() {
        String body = "{"
                + "\"businessRegistrationNumber\":\"1234567890\","
                + "\"name\":\"강남점\","
                + "\"representativeName\":\"김사장\","
                + "\"roadAddress\":\"서울 강남구 테헤란로 1\","
                + "\"phone\":\"0212345678\","
                + "\"radiusM\":500"
                + "}";

        assertThrows(
                JsonProcessingException.class,
                () -> objectMapper.readValue(body, WorkplaceCreateRequest.class));
    }

    @Test
    void rejectsBodyCarryingOwnerIdentifier() {
        String body = "{"
                + "\"businessRegistrationNumber\":\"1234567890\","
                + "\"name\":\"강남점\","
                + "\"representativeName\":\"김사장\","
                + "\"roadAddress\":\"서울 강남구 테헤란로 1\","
                + "\"phone\":\"0212345678\","
                + "\"ownerUserId\":999"
                + "}";

        assertThrows(
                JsonProcessingException.class,
                () -> objectMapper.readValue(body, WorkplaceCreateRequest.class));
    }
}
