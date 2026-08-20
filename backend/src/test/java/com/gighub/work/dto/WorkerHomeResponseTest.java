package com.gighub.work.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gighub.config.ApiJsonMapper;
import com.gighub.work.domain.WorkCaseStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void serializesTaxReferenceFromTheStoredWorkerPayout() throws Exception {
        JsonNode todayWorkCase = objectMapper
                .readTree(objectMapper.writeValueAsString(responseWithWorkerPaidAmount(200_000L)))
                .path("todayWorkCase");

        assertFalse(todayWorkCase.has("expectedNetAmount"));
        assertEquals(200_000L, todayWorkCase.path("taxReference").path("basisAmount").asLong());
        assertEquals(
                1_480L,
                todayWorkCase.path("taxReference").path("estimatedTaxAmount").asLong());
        assertEquals(
                198_520L,
                todayWorkCase.path("taxReference").path("estimatedAfterTaxAmount").asLong());
    }

    @Test
    void keepsTaxReferenceNullUntilThePayoutSnapshotExists() throws Exception {
        JsonNode todayWorkCase = objectMapper
                .readTree(objectMapper.writeValueAsString(responseWithWorkerPaidAmount(null)))
                .path("todayWorkCase");

        assertTrue(todayWorkCase.path("taxReference").isNull());
        assertFalse(todayWorkCase.has("expectedNetAmount"));
    }

    @Test
    void zeroPayoutStillHasAZeroValuedTaxReference() {
        WorkerHomeResponse.TaxReference taxReference = responseWithWorkerPaidAmount(0L)
                .getTodayWorkCase()
                .getTaxReference();

        assertNotNull(taxReference);
        assertEquals(0L, taxReference.getBasisAmount());
        assertEquals(0L, taxReference.getEstimatedTaxAmount());
        assertEquals(0L, taxReference.getEstimatedAfterTaxAmount());
    }

    private WorkerHomeResponse lateResponse() {
        return responseWithWorkerPaidAmount(null);
    }

    private WorkerHomeResponse responseWithWorkerPaidAmount(Long workerPaidAmount) {
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
                null,
                workerPaidAmount);
    }
}
