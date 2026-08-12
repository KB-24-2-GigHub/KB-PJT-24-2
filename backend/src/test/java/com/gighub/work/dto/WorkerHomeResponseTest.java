package com.gighub.work.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gighub.config.ApiJsonMapper;
import com.gighub.work.domain.WorkCaseStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerHomeResponseTest {

    private final ObjectMapper objectMapper = ApiJsonMapper.create();

    /**
     * {@code isLate}는 Getter 이름 규칙에 따라 조용히 {@code "late"}로 직렬화될 수 있어
     * 필드 이름 자체를 고정합니다.
     */
    @Test
    void serializesLatenessUnderApprovedFieldNames() throws Exception {
        JsonNode attendance = objectMapper
                .readTree(objectMapper.writeValueAsString(lateResponse()))
                .path("todayWorkCase")
                .path("attendance");

        assertTrue(attendance.has("isLate"), "attendance.isLate 필드가 있어야 합니다.");
        assertTrue(attendance.path("isLate").asBoolean(), "지각이면 isLate가 true여야 합니다.");
        assertEquals(30, attendance.path("lateMinutes").asInt());
    }

    private WorkerHomeResponse lateResponse() {
        LocalDateTime startsAt = LocalDateTime.of(2026, 8, 11, 9, 0);
        return WorkerHomeResponse.of(
                1L,
                "주방 보조",
                "행복식당",
                startsAt,
                startsAt.plusHours(9),
                60,
                false,
                200_000L,
                WorkCaseStatus.IN_PROGRESS,
                startsAt.plusMinutes(30),
                startsAt.plusMinutes(30),
                null,
                "HELD",
                null,
                null);
    }
}
