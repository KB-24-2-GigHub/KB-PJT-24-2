package com.gighub.work.domain;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttendanceStateIntegrityTest {

    private static final LocalDateTime CHECKED_IN_AT = LocalDateTime.of(2026, 8, 11, 9, 0);
    private static final LocalDateTime CHECKED_OUT_AT = LocalDateTime.of(2026, 8, 11, 18, 0);

    @Test
    void inProgressWithCheckInIsConsistent() {
        assertTrue(AttendanceStateIntegrity.verify(
                WorkCaseStatus.IN_PROGRESS, CHECKED_IN_AT, null).isEmpty());
    }

    @Test
    void completedWithoutAttendanceIsConsistent() {
        // ACCEPTED·READY에서 근태 없이 COMPLETED로 가는 전이가 허용되므로 모순이 아니다.
        assertTrue(AttendanceStateIntegrity.verify(
                WorkCaseStatus.COMPLETED, null, null).isEmpty());
    }

    @Test
    void flagsInProgressWithoutCheckIn() {
        assertEquals(
                java.util.List.of(AttendanceStateViolation.MISSING_CHECK_IN_ON_IN_PROGRESS),
                AttendanceStateIntegrity.verify(WorkCaseStatus.IN_PROGRESS, null, null));
    }

    @Test
    void flagsNoShowThatHasAttendance() {
        assertEquals(
                java.util.List.of(AttendanceStateViolation.ATTENDANCE_ON_NO_SHOW),
                AttendanceStateIntegrity.verify(WorkCaseStatus.NO_SHOW, CHECKED_IN_AT, null));
    }

    @Test
    void flagsCheckOutWithoutCheckIn() {
        assertTrue(AttendanceStateIntegrity.verify(WorkCaseStatus.COMPLETED, null, CHECKED_OUT_AT)
                .contains(AttendanceStateViolation.CHECK_OUT_WITHOUT_CHECK_IN));
    }

    @Test
    void flagsCheckOutMissingThatAlreadyCheckedOut() {
        assertTrue(AttendanceStateIntegrity
                .verify(WorkCaseStatus.CHECK_OUT_MISSING, CHECKED_IN_AT, CHECKED_OUT_AT)
                .contains(AttendanceStateViolation.CHECK_OUT_ON_CHECK_OUT_MISSING));
    }
}
