package com.gighub.invitation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gighub.common.api.ApiResponse;
import com.gighub.config.ApiJsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 초대 조회 응답이 승인 명세의 필드 집합과 표현으로 직렬화되는지 확인합니다.
 */
class InvitationDetailResponseJsonTest {

    private final ObjectMapper objectMapper = ApiJsonMapper.create();

    @Test
    void serializesApprovedFieldsWithUtcInstantsAndIntegerWage() throws Exception {
        JsonNode data = objectMapper
                .readTree(objectMapper.writeValueAsString(ApiResponse.of(response(
                        OwnerBadgeResponse.of("TRUST_OWNER", 2)))))
                .get("data");

        assertEquals("주말 홀 서빙", data.get("title").asText());
        assertEquals("강남점", data.get("workplaceName").asText());
        assertEquals("2026-08-20T01:00:00Z", data.get("startsAt").asText());
        assertEquals("2026-08-20T09:00:00Z", data.get("endsAt").asText());
        assertEquals(60, data.get("breakMinutes").asInt());
        assertFalse(data.get("breakPaid").asBoolean());
        assertEquals(120000, data.get("dailyWage").asLong());
        assertTrue(data.get("dailyWage").isIntegralNumber(), "금액은 원 단위 정수여야 합니다.");
        assertFalse(data.has("termsVersion"), "내부 조건 Version은 응답에 없어야 합니다.");
        assertEquals("2026-08-20T01:00:00Z", data.get("expiresAt").asText());
        assertEquals("TRUST_OWNER", data.get("ownerBadge").get("badgeType").asText());
        assertEquals(2, data.get("ownerBadge").get("level").asInt());
    }

    @Test
    void ownerBadgeIsAnExplicitNullWhenTheOwnerHasNoActiveBadge() throws Exception {
        JsonNode data = objectMapper
                .readTree(objectMapper.writeValueAsString(ApiResponse.of(response(null))))
                .get("data");

        assertTrue(data.has("ownerBadge"), "필드를 생략하지 않고 null로 보내야 합니다.");
        assertTrue(data.get("ownerBadge").isNull());
    }

    @Test
    void identifiersAndCoordinatesAreNotPartOfTheResponse() throws Exception {
        JsonNode data = objectMapper
                .readTree(objectMapper.writeValueAsString(ApiResponse.of(response(null))))
                .get("data");

        // 초대 화면에 필요 없는 값은 응답 Shape 자체에 없어야 합니다.
        for (String absent : new String[]{
                "workCaseId", "employerId", "workerId", "invitationId",
                "workplaceId", "workplaceLatitude", "workplaceLongitude",
                "token", "tokenHash", "status"
        }) {
            assertFalse(data.has(absent), absent + " 필드는 응답에 없어야 합니다.");
        }
    }

    private InvitationDetailResponse response(OwnerBadgeResponse ownerBadge) {
        return InvitationDetailResponse.of(
                "주말 홀 서빙",
                "강남점",
                Instant.parse("2026-08-20T01:00:00Z"),
                Instant.parse("2026-08-20T09:00:00Z"),
                60,
                false,
                120_000L,
                Instant.parse("2026-08-20T01:00:00Z"),
                ownerBadge
        );
    }
}
