package com.gighub.contract.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gighub.config.ApiJsonMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 계약 Snapshot이 승인 명세의 닫힌 JSON Shape 그대로 굳는지 확인합니다.
 *
 * <p>이 Shape는 저장 후 되돌릴 수 없습니다. 필드가 빠지거나 표현이 달라지면 이미 확정된
 * 계약서를 다시 만들 수 없으므로 값 하나까지 고정합니다.</p>
 */
class ContractTermsSnapshotJsonTest {

    private final ObjectMapper objectMapper = ApiJsonMapper.create();

    @Test
    void serializesTheApprovedClosedShape() throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(snapshot()));

        assertEquals(1, json.get("schemaVersion").asInt());
        assertEquals(3, json.get("termsVersion").asInt());
        assertEquals("주말 홀 서빙", json.get("title").asText());
        assertEquals("2026-08-20T01:00:00Z", json.get("startsAt").asText());
        assertEquals("2026-08-20T09:00:00Z", json.get("endsAt").asText());
        assertEquals(60, json.get("breakMinutes").asInt());
        assertFalse(json.get("breakPaid").asBoolean());
        assertEquals("강남점", json.get("workplaceName").asText());
        assertEquals(
                "서울특별시 강남구 테헤란로 1 2층",
                json.get("workplaceAddress").asText());
        assertEquals(120000, json.get("dailyWage").asLong());
        assertEquals(7, json.get("owner").get("userId").asLong());
        assertEquals("김사장", json.get("owner").get("name").asText());
        assertEquals(42, json.get("worker").get("userId").asLong());
        assertEquals("이알바", json.get("worker").get("name").asText());

        assertEquals(15, json.size(), "승인된 필드 수와 같아야 합니다.");
    }

    @Test
    void decimalColumnsDropTrailingZerosToMatchTheApprovedShape() throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(snapshot()));

        // DB는 DECIMAL(8,2)로 100.00을 돌려주지만 승인 Shape는 100입니다.
        assertEquals("100", json.get("allowedRadiusMeters").asText());
        assertEquals("37.498", json.get("workplaceLatitude").asText());
        assertEquals("127.027", json.get("workplaceLongitude").asText());
    }

    @Test
    void missingCoordinatesStayNullInsteadOfZero() throws Exception {
        ContractTermsSnapshot withoutCoordinates = builder()
                .workplaceLatitude(null)
                .workplaceLongitude(null)
                .build();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(withoutCoordinates));

        // 좌표는 nullable입니다. 0으로 채우면 아프리카 앞바다를 근무지로 굳히게 됩니다.
        assertTrue(json.get("workplaceLatitude").isNull());
        assertTrue(json.get("workplaceLongitude").isNull());
    }

    @Test
    void snapshotCannotBeChangedAfterItIsBuilt() {
        // 확정 순간을 증명하는 값이라 Setter를 두지 않습니다.
        for (java.lang.reflect.Method method : ContractTermsSnapshot.class.getMethods()) {
            assertFalse(
                    method.getName().startsWith("set"),
                    "계약 Snapshot에는 Setter가 없어야 합니다: " + method.getName()
            );
        }
    }

    private static ContractTermsSnapshot snapshot() {
        return builder().build();
    }

    private static ContractTermsSnapshot.Builder builder() {
        return ContractTermsSnapshot.builder()
                .termsVersion(3)
                .title("주말 홀 서빙")
                .startsAt(Instant.parse("2026-08-20T01:00:00Z"))
                .endsAt(Instant.parse("2026-08-20T09:00:00Z"))
                .breakMinutes(60)
                .breakPaid(false)
                .workplaceName("강남점")
                .workplaceAddress("서울특별시 강남구 테헤란로 1 2층")
                .workplaceLatitude(new BigDecimal("37.4980000"))
                .workplaceLongitude(new BigDecimal("127.0270000"))
                .allowedRadiusMeters(new BigDecimal("100.00"))
                .dailyWage(120_000L)
                .owner(7L, "김사장")
                .worker(42L, "이알바");
    }
}
