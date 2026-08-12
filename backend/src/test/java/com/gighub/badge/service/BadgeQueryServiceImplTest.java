package com.gighub.badge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gighub.badge.dto.UserBadgeListResponse;
import com.gighub.badge.mapper.BadgeQueryMapper;
import com.gighub.badge.mapper.result.UserBadgeRow;
import com.gighub.common.api.ApiResponse;
import com.gighub.config.ApiJsonMapper;
import org.junit.jupiter.api.Test;

class BadgeQueryServiceImplTest {

    private final BadgeQueryMapper badgeQueryMapper = mock(BadgeQueryMapper.class);
    private final BadgeQueryServiceImpl service = new BadgeQueryServiceImpl(badgeQueryMapper);
    private final ObjectMapper objectMapper = ApiJsonMapper.create();

    /** #182가 대체하기 전 legacy 목록의 필드와 값은 타입 경계 변경 뒤에도 같아야 합니다. */
    @Test
    void preservesLegacyBadgeJsonWhileSeparatingPersistenceRow() throws Exception {
        LocalDateTime awardedAt = LocalDateTime.of(2026, 8, 10, 12, 30);
        String evidence = "{\"ruleVersion\":\"legacy\"}";
        when(badgeQueryMapper.findBadgesByUserId(7L)).thenReturn(List.of(
                new UserBadgeRow(11L, 7L, "TRUST_OWNER", evidence, awardedAt)));

        UserBadgeListResponse response = service.findByUserId(7L);
        JsonNode data = objectMapper.readTree(
                objectMapper.writeValueAsString(ApiResponse.of(response))).path("data");
        JsonNode item = data.path("items").get(0);

        assertEquals(Set.of("items"), fieldNames(data));
        assertEquals(
                Set.of("id", "userId", "badgeType", "evidence", "createdAt"),
                fieldNames(item));
        assertEquals(11L, item.path("id").asLong());
        assertEquals(7L, item.path("userId").asLong());
        assertEquals("TRUST_OWNER", item.path("badgeType").asText());
        assertEquals(evidence, item.path("evidence").asText());
        assertEquals("2026-08-10T12:30:00", item.path("createdAt").asText());
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
