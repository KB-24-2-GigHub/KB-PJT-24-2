package com.gighub.attendance.service;

import com.gighub.attendance.mapper.AttendanceLifecycleMapper;
import com.gighub.attendance.mapper.result.AttendanceLifecycleWorkCaseRow;
import com.gighub.attendance.mapper.result.AttendanceReadinessCheckRow;
import com.gighub.work.domain.WorkCaseStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttendanceLifecycleTransitionExecutorTest {

    private static final long WORK_CASE_ID = 17L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 7, 9, 0);

    @Mock
    private AttendanceLifecycleMapper lifecycleMapper;

    @Mock
    private SignedContractArtifactVerifier artifactVerifier;

    @Test
    void advancesAcceptedWorkAtReadyBoundaryWhenAggregateAndArtifactAreComplete() {
        when(lifecycleMapper.lockById(WORK_CASE_ID))
                .thenReturn(row(WorkCaseStatus.ACCEPTED, NOW.plusMinutes(30), NOW.plusHours(8)));
        when(lifecycleMapper.findReadinessCheck(WORK_CASE_ID)).thenReturn(completeReadiness());
        when(artifactVerifier.isReadable(WORK_CASE_ID)).thenReturn(true);
        when(lifecycleMapper.transitionStatus(
                WORK_CASE_ID, WorkCaseStatus.ACCEPTED, WorkCaseStatus.READY))
                .thenReturn(1);

        assertTrue(executor().advanceToReady(WORK_CASE_ID, NOW));
    }

    @Test
    void leavesAcceptedWorkBlockedWhenAggregateIsIncomplete() {
        AttendanceReadinessCheckRow readiness = completeReadiness();
        readiness.setEscrowHeld(false);
        when(lifecycleMapper.lockById(WORK_CASE_ID))
                .thenReturn(row(WorkCaseStatus.ACCEPTED, NOW, NOW.plusHours(8)));
        when(lifecycleMapper.findReadinessCheck(WORK_CASE_ID)).thenReturn(readiness);

        assertFalse(executor().advanceToReady(WORK_CASE_ID, NOW));

        verify(artifactVerifier, never()).isReadable(WORK_CASE_ID);
        verify(lifecycleMapper, never()).transitionStatus(
                WORK_CASE_ID, WorkCaseStatus.ACCEPTED, WorkCaseStatus.READY);
    }

    @Test
    void leavesAcceptedWorkBlockedWhenSignedArtifactIsUnreadable() {
        when(lifecycleMapper.lockById(WORK_CASE_ID))
                .thenReturn(row(WorkCaseStatus.ACCEPTED, NOW, NOW.plusHours(8)));
        when(lifecycleMapper.findReadinessCheck(WORK_CASE_ID)).thenReturn(completeReadiness());

        assertFalse(executor().advanceToReady(WORK_CASE_ID, NOW));

        verify(lifecycleMapper, never()).transitionStatus(
                WORK_CASE_ID, WorkCaseStatus.ACCEPTED, WorkCaseStatus.READY);
    }

    @Test
    void doesNotEnterReadyAtNoShowBoundary() {
        when(lifecycleMapper.lockById(WORK_CASE_ID))
                .thenReturn(row(WorkCaseStatus.ACCEPTED, NOW.minusHours(1), NOW.plusHours(7)));

        assertFalse(executor().advanceToReady(WORK_CASE_ID, NOW));

        verify(lifecycleMapper, never()).findReadinessCheck(WORK_CASE_ID);
    }

    @Test
    void advancesReadyWorkToNoShowAtOneHourBoundary() {
        when(lifecycleMapper.lockById(WORK_CASE_ID))
                .thenReturn(row(WorkCaseStatus.READY, NOW.minusHours(1), NOW.plusHours(7)));
        when(lifecycleMapper.transitionStatus(
                WORK_CASE_ID, WorkCaseStatus.READY, WorkCaseStatus.NO_SHOW))
                .thenReturn(1);

        assertTrue(executor().advanceToNoShow(WORK_CASE_ID, NOW));
    }

    @Test
    void preservesReadyWorkWhenSuccessfulCheckInExists() {
        when(lifecycleMapper.lockById(WORK_CASE_ID))
                .thenReturn(row(WorkCaseStatus.READY, NOW.minusHours(1), NOW.plusHours(7)));
        when(lifecycleMapper.hasSuccessfulAttendance(WORK_CASE_ID, "CHECK_IN"))
                .thenReturn(true);

        assertFalse(executor().advanceToNoShow(WORK_CASE_ID, NOW));

        verify(lifecycleMapper, never()).transitionStatus(
                WORK_CASE_ID, WorkCaseStatus.READY, WorkCaseStatus.NO_SHOW);
    }

    @Test
    void advancesInProgressWorkToCheckoutMissingAtTwoHourBoundary() {
        when(lifecycleMapper.lockById(WORK_CASE_ID))
                .thenReturn(row(
                        WorkCaseStatus.IN_PROGRESS,
                        NOW.minusHours(10),
                        NOW.minusHours(2)));
        when(lifecycleMapper.hasSuccessfulAttendance(WORK_CASE_ID, "CHECK_IN"))
                .thenReturn(true);
        when(lifecycleMapper.transitionStatus(
                WORK_CASE_ID,
                WorkCaseStatus.IN_PROGRESS,
                WorkCaseStatus.CHECK_OUT_MISSING))
                .thenReturn(1);

        assertTrue(executor().advanceToCheckoutMissing(WORK_CASE_ID, NOW));
    }

    @Test
    void preservesInProgressWorkWhenSuccessfulCheckoutExists() {
        when(lifecycleMapper.lockById(WORK_CASE_ID))
                .thenReturn(row(
                        WorkCaseStatus.IN_PROGRESS,
                        NOW.minusHours(10),
                        NOW.minusHours(2)));
        when(lifecycleMapper.hasSuccessfulAttendance(WORK_CASE_ID, "CHECK_IN"))
                .thenReturn(true);
        when(lifecycleMapper.hasSuccessfulAttendance(WORK_CASE_ID, "CHECK_OUT"))
                .thenReturn(true);

        assertFalse(executor().advanceToCheckoutMissing(WORK_CASE_ID, NOW));

        verify(lifecycleMapper, never()).transitionStatus(
                WORK_CASE_ID,
                WorkCaseStatus.IN_PROGRESS,
                WorkCaseStatus.CHECK_OUT_MISSING);
    }

    private AttendanceLifecycleTransitionExecutor executor() {
        return new AttendanceLifecycleTransitionExecutor(lifecycleMapper, artifactVerifier);
    }

    private AttendanceLifecycleWorkCaseRow row(
            WorkCaseStatus status,
            LocalDateTime startsAt,
            LocalDateTime endsAt) {
        return AttendanceLifecycleWorkCaseRow.builder()
                .workCaseId(WORK_CASE_ID)
                .status(status)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .build();
    }

    private AttendanceReadinessCheckRow completeReadiness() {
        AttendanceReadinessCheckRow row = new AttendanceReadinessCheckRow();
        row.setWorkerAssigned(true);
        row.setInvitationAccepted(true);
        row.setContractMatched(true);
        row.setEscrowHeld(true);
        row.setSettlementWaiting(true);
        row.setWorkplaceLocated(true);
        row.setSignedContractRecorded(true);
        return row;
    }
}
