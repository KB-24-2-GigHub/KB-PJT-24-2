package com.gighub.work.dto;

import java.util.Map;

import com.gighub.work.domain.WorkCaseStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkCaseSummaryResponseTest {

    @Test
    void fillsMissingStatusesWithZero() {
        WorkCaseSummaryResponse response = WorkCaseSummaryResponse.of(Map.of(
                WorkCaseStatus.DRAFT, 2L,
                WorkCaseStatus.COMPLETED, 8L));

        assertEquals(2, response.getDraft());
        assertEquals(0, response.getAccepted());
        assertEquals(0, response.getReady());
        assertEquals(0, response.getInProgress());
        assertEquals(0, response.getCheckOutMissing());
        assertEquals(8, response.getCompleted());
        assertEquals(0, response.getNoShow());
        assertEquals(0, response.getCanceled());
    }

    @Test
    void returnsAllZerosWhenNoWorkCasesExist() {
        WorkCaseSummaryResponse response = WorkCaseSummaryResponse.of(Map.of());

        assertEquals(0, response.getDraft());
        assertEquals(0, response.getAccepted());
        assertEquals(0, response.getReady());
        assertEquals(0, response.getInProgress());
        assertEquals(0, response.getCheckOutMissing());
        assertEquals(0, response.getCompleted());
        assertEquals(0, response.getNoShow());
        assertEquals(0, response.getCanceled());
    }

}
