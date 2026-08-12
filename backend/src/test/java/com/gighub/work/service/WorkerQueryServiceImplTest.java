package com.gighub.work.service;

import java.time.LocalDateTime;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.api.PageResponse;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.member.domain.UserRole;
import com.gighub.work.dto.WorkerHomeResponse;
import com.gighub.work.dto.WorkerWorkCaseListItemResponse;
import com.gighub.work.mapper.WorkerMapper;
import com.gighub.work.mapper.param.WorkerWorkCaseListQuery;
import com.gighub.work.service.impl.WorkerQueryServiceImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkerQueryServiceImplTest {

    private static final Long WORKER_ID = 42L;

    private final WorkerMapper workerMapper = mock(WorkerMapper.class);
    private final WorkerQueryServiceImpl service = new WorkerQueryServiceImpl(workerMapper);

    @Test
    void homeRejectsNonWorker() {
        assertThrows(RoleMismatchException.class, () -> service.home(owner()));

        verifyNoInteractions(workerMapper);
    }

    @Test
    void workCasesRejectsNonWorker() {
        assertThrows(RoleMismatchException.class, () -> service.workCases(owner(), 0, 20));

        verifyNoInteractions(workerMapper);
    }

    @Test
    void homeReturnsNullTodayWorkCaseWhenNoCandidateExists() {
        when(workerMapper.findTodayCandidate(
                anyLong(),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class))).thenReturn(null);

        WorkerHomeResponse response = service.home(worker());

        assertNull(response.getTodayWorkCase());
    }

    @Test
    void workCasesReturnsEmptyPageWhenWorkerHasNoHistory() {
        when(workerMapper.findPage(any(WorkerWorkCaseListQuery.class))).thenReturn(java.util.List.of());
        when(workerMapper.countByWorker(any(WorkerWorkCaseListQuery.class))).thenReturn(0L);

        PageResponse<WorkerWorkCaseListItemResponse> response = service.workCases(worker(), 0, 20);

        assertEquals(0, response.getContent().size());
    }

    private AuthPrincipal worker() {
        return new AuthPrincipal(WORKER_ID, UserRole.WORKER, "근로자");
    }

    private AuthPrincipal owner() {
        return new AuthPrincipal(1L, UserRole.OWNER, "사업주");
    }
}
