package com.gighub.work.dto;

import java.time.LocalDateTime;

import com.gighub.work.domain.WorkCaseStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkCaseListItemResponseTest {

    @Test
    void returnsNullWorkerWhenUnmatched() {
        WorkCaseListItemResponse response = response(null, null);

        assertNull(response.getWorker());
    }

    @Test
    void returnsWorkerSummaryWhenMatched() {
        WorkCaseListItemResponse response = response(42L, "이알바");

        assertNotNull(response.getWorker());
        assertEquals(42L, response.getWorker().getWorkerId());
        assertEquals("이알바", response.getWorker().getName());
    }

    @Test
    void derivesWorkDateFromStartsAt() {
        WorkCaseListItemResponse response = response(null, null);

        assertEquals(LocalDateTime.of(2026, 8, 10, 9, 0).toLocalDate(), response.getWorkDate());
    }

    private WorkCaseListItemResponse response(Long workerId, String workerName) {
        return WorkCaseListItemResponse.of(
                101L,
                "주말 카페 서빙",
                LocalDateTime.of(2026, 8, 10, 9, 0),
                LocalDateTime.of(2026, 8, 10, 18, 0),
                120_000L,
                WorkCaseStatus.READY,
                workerId,
                workerName);
    }
}
