package com.gighub.attendance.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AttendanceWindowPolicyTest {

    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 8, 14, 13, 53);

    @Test
    void shortWorkUsesScheduledEndAsNoShowBoundary() {
        LocalDateTime endsAt = STARTS_AT.plusMinutes(2);

        assertEquals(endsAt, AttendanceWindowPolicy.noShowAt(STARTS_AT, endsAt));
    }

    @Test
    void longWorkKeepsOneHourNoShowBoundary() {
        LocalDateTime endsAt = STARTS_AT.plusHours(8);

        assertEquals(
                STARTS_AT.plusHours(1),
                AttendanceWindowPolicy.noShowAt(STARTS_AT, endsAt));
    }

    @Test
    void oneHourWorkUsesTheSharedBoundary() {
        LocalDateTime sharedBoundary = STARTS_AT.plusHours(1);

        assertEquals(
                sharedBoundary,
                AttendanceWindowPolicy.noShowAt(STARTS_AT, sharedBoundary));
    }

    @Test
    void noShowBoundaryRequiresBothScheduledTimes() {
        LocalDateTime endsAt = STARTS_AT.plusHours(8);

        assertThrows(
                NullPointerException.class,
                () -> AttendanceWindowPolicy.noShowAt(null, endsAt));
        assertThrows(
                NullPointerException.class,
                () -> AttendanceWindowPolicy.noShowAt(STARTS_AT, null));
    }

    @Test
    void schedulerNoShowBoundariesAreReturnedAsOneValue() {
        LocalDateTime now = STARTS_AT.plusHours(2);

        AttendanceWindowPolicy.NoShowCandidateWindow window =
                AttendanceWindowPolicy.noShowCandidateWindow(now);

        assertEquals(now.minusHours(1), window.latestStartsAt());
        assertEquals(now, window.latestEndsAt());
    }
}
