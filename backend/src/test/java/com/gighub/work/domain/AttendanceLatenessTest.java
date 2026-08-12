package com.gighub.work.domain;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttendanceLatenessTest {

    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 8, 11, 9, 0, 0);

    @Test
    void notLateWhenCheckInHasNotHappenedYet() {
        AttendanceLateness result = AttendanceLateness.from(STARTS_AT, null);

        assertFalse(result.isLate());
        assertEquals(0, result.getLateMinutes());
    }

    @Test
    void notLateWhenCheckInIsExactlyOnTime() {
        AttendanceLateness result = AttendanceLateness.from(STARTS_AT, STARTS_AT);

        assertFalse(result.isLate());
    }

    @Test
    void roundsPartialLateMinuteUp() {
        AttendanceLateness result = AttendanceLateness.from(STARTS_AT, STARTS_AT.plusSeconds(1));

        assertTrue(result.isLate());
        assertEquals(1, result.getLateMinutes());
    }
}
