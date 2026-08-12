package com.gighub.contract.mapper.param;

import com.gighub.contract.domain.ContractTermsSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkContractInsertParamTest {

    @Test
    void relationalColumnsAndStoredJsonShareOneDomainSnapshot() {
        ContractTermsSnapshot terms = ContractTermsSnapshot.builder()
                .termsVersion(3)
                .title("주말 홀 서빙")
                .startsAt(Instant.parse("2026-08-10T09:00:00Z"))
                .endsAt(Instant.parse("2026-08-10T17:00:00Z"))
                .breakMinutes(60)
                .breakPaid(false)
                .workplaceName("강남점")
                .workplaceAddress("서울 강남구")
                .workplaceLatitude(new BigDecimal("37.5000000"))
                .workplaceLongitude(new BigDecimal("127.0000000"))
                .allowedRadiusMeters(new BigDecimal("100.00"))
                .dailyWage(120_000L)
                .owner(7L, "사장")
                .worker(9L, "근로자")
                .build();
        LocalDateTime acceptedAt = LocalDateTime.of(2026, 8, 10, 12, 0);

        WorkContractInsertParam param =
                WorkContractInsertParam.from(11L, terms, "{\"schemaVersion\":1}", acceptedAt);

        assertEquals(11L, param.getWorkCaseId());
        assertEquals(7L, param.getEmployerId());
        assertEquals(9L, param.getWorkerId());
        assertEquals("주말 홀 서빙", param.getTitle());
        assertEquals(LocalDateTime.of(2026, 8, 10, 18, 0), param.getStartsAt());
        assertEquals(LocalDateTime.of(2026, 8, 11, 2, 0), param.getEndsAt());
        assertEquals(3, param.getSourceTermsVersion());
        assertEquals(120_000L, param.getDailyWage());
        assertEquals("{\"schemaVersion\":1}", param.getTermsSnapshotJson());
        assertEquals(acceptedAt, param.getAcceptedAt());
    }
}
