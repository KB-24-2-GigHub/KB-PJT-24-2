package com.gighub.work.service;

import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.mapper.WorkCaseMapper;
import com.gighub.work.mapper.result.WorkCaseLockRow;
import com.gighub.work.service.impl.WorkLifecycleCommandServiceImpl;
import com.gighub.work.service.result.WorkLifecycleSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkLifecycleCommandServiceImplTest {

    private static final long WORK_CASE_ID = 17L;
    private static final LocalDateTime STARTS_AT =
            LocalDateTime.of(2026, 8, 11, 9, 0);
    private static final LocalDateTime ENDS_AT = STARTS_AT.plusHours(8);

    @Mock
    private WorkCaseMapper workCaseMapper;

    @InjectMocks
    private WorkLifecycleCommandServiceImpl service;

    @Test
    void lockReturnsOnlyThePublicLifecycleSnapshot() {
        when(workCaseMapper.lockById(WORK_CASE_ID)).thenReturn(WorkCaseLockRow.builder()
                .workCaseId(WORK_CASE_ID)
                .employerId(3L)
                .workplaceId(5L)
                .status(WorkCaseStatus.READY)
                .termsVersion(2)
                .startsAt(STARTS_AT)
                .endsAt(ENDS_AT)
                .agreedWage(100_000L)
                .breakMinutes(30)
                .breakPaid(false)
                .build());

        WorkLifecycleSnapshot snapshot = service.lock(WORK_CASE_ID);

        assertEquals(WORK_CASE_ID, snapshot.workCaseId());
        assertEquals(WorkCaseStatus.READY, snapshot.status());
        assertEquals(STARTS_AT, snapshot.startsAt());
        assertEquals(ENDS_AT, snapshot.endsAt());
        assertEquals(100_000L, snapshot.agreedWage());
        assertEquals(30, snapshot.breakMinutes());
        assertFalse(snapshot.breakPaid());
    }

    @Test
    void lockReturnsNullWhenTheWorkCaseDoesNotExist() {
        assertNull(service.lock(WORK_CASE_ID));
    }

    @Test
    void transitionAppliesDomainPolicyAndExpectedStateRowCount() {
        when(workCaseMapper.updateWorkStatus(
                WORK_CASE_ID,
                List.of(WorkCaseStatus.READY),
                WorkCaseStatus.NO_SHOW)).thenReturn(1);
        when(workCaseMapper.updateWorkStatus(
                WORK_CASE_ID,
                List.of(WorkCaseStatus.ACCEPTED),
                WorkCaseStatus.NO_SHOW)).thenReturn(1);

        assertTrue(service.transition(
                WORK_CASE_ID, WorkCaseStatus.READY, WorkCaseStatus.NO_SHOW));
        assertTrue(service.transition(
                WORK_CASE_ID, WorkCaseStatus.ACCEPTED, WorkCaseStatus.NO_SHOW));
        assertFalse(service.transition(
                WORK_CASE_ID, WorkCaseStatus.DRAFT, WorkCaseStatus.NO_SHOW));

        verify(workCaseMapper, times(1)).updateWorkStatus(
                WORK_CASE_ID,
                List.of(WorkCaseStatus.READY),
                WorkCaseStatus.NO_SHOW);
        verify(workCaseMapper).updateWorkStatus(
                WORK_CASE_ID,
                List.of(WorkCaseStatus.ACCEPTED),
                WorkCaseStatus.NO_SHOW);
    }
}
