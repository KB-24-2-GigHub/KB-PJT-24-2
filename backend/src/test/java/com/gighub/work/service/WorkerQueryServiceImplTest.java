package com.gighub.work.service;

import java.time.LocalDateTime;
import java.util.List;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.api.ApiTimes;
import com.gighub.common.api.PageResponse;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.common.exception.ValidationException;
import com.gighub.member.domain.UserRole;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.dto.ShareableWorkplaceListItemResponse;
import com.gighub.work.dto.WorkerHomeResponse;
import com.gighub.work.dto.WorkerWorkCaseListItemResponse;
import com.gighub.work.mapper.WorkerMapper;
import com.gighub.work.mapper.param.ShareableWorkplaceListQuery;
import com.gighub.work.mapper.param.WorkerWorkCaseListQuery;
import com.gighub.work.mapper.result.ShareableWorkplaceRow;
import com.gighub.work.mapper.result.WorkerHomeCandidateRow;
import com.gighub.work.service.impl.WorkerQueryServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
    void homeBuildsTheTaxReferenceFromTheStoredWorkerPayout() {
        LocalDateTime startsAt = LocalDateTime.of(2026, 8, 20, 9, 0);
        when(workerMapper.findTodayCandidate(
                anyLong(),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(LocalDateTime.class))).thenReturn(WorkerHomeCandidateRow.builder()
                        .workCaseId(11L)
                        .title("홀 서빙")
                        .workplaceName("강남점")
                        .startsAt(startsAt)
                        .endsAt(startsAt.plusHours(8))
                        .breakMinutes(60)
                        .breakPaid(false)
                        .dailyWage(300_000L)
                        .status(WorkCaseStatus.ACCEPTED)
                        .workerPaidAmount(200_000L)
                        .build());

        WorkerHomeResponse.TaxReference taxReference = service.home(worker())
                .getTodayWorkCase()
                .getTaxReference();

        assertNotNull(taxReference);
        assertEquals(200_000L, taxReference.getBasisAmount());
        assertEquals(1_480L, taxReference.getEstimatedTaxAmount());
        assertEquals(198_520L, taxReference.getEstimatedAfterTaxAmount());
    }

    @Test
    void workCasesReturnsEmptyPageWhenWorkerHasNoHistory() {
        when(workerMapper.findPage(any(WorkerWorkCaseListQuery.class))).thenReturn(java.util.List.of());
        when(workerMapper.countByWorker(any(WorkerWorkCaseListQuery.class))).thenReturn(0L);

        PageResponse<WorkerWorkCaseListItemResponse> response = service.workCases(worker(), 0, 20);

        assertEquals(0, response.getContent().size());
    }

    @Test
    void shareableWorkplacesRejectsNonWorker() {
        assertThrows(
                RoleMismatchException.class, () -> service.shareableWorkplaces(owner(), 0, 20));

        verifyNoInteractions(workerMapper);
    }

    @Test
    void shareableWorkplacesRejectsOutOfRangePageQuery() {
        assertThrows(
                ValidationException.class, () -> service.shareableWorkplaces(worker(), 0, 101));

        verifyNoInteractions(workerMapper);
    }

    @Test
    void shareableWorkplacesQueriesTheAuthenticatedWorkerWithTheRequestedPageWindow() {
        when(workerMapper.findShareableWorkplacePage(any(ShareableWorkplaceListQuery.class)))
                .thenReturn(List.of());
        when(workerMapper.countShareableWorkplaces(any(ShareableWorkplaceListQuery.class)))
                .thenReturn(0L);

        service.shareableWorkplaces(worker(), 2, 10);

        ArgumentCaptor<ShareableWorkplaceListQuery> captor =
                ArgumentCaptor.forClass(ShareableWorkplaceListQuery.class);
        verify(workerMapper).findShareableWorkplacePage(captor.capture());
        // Client가 보낸 값이 아니라 Session principal의 사용자 ID로만 조회한다.
        assertEquals(WORKER_ID, captor.getValue().getWorkerId());
        assertEquals(10, captor.getValue().getSize());
        assertEquals(20L, captor.getValue().getOffset());
    }

    @Test
    void shareableWorkplacesMapsOnlyTheApprovedItemFields() {
        when(workerMapper.findShareableWorkplacePage(any(ShareableWorkplaceListQuery.class)))
                .thenReturn(List.of(ShareableWorkplaceRow.builder()
                        .workplaceId(9L)
                        .workplaceName("강남점")
                        .ownerName("김대표")
                        .startsAt(LocalDateTime.of(2026, 8, 20, 10, 0))
                        .endsAt(LocalDateTime.of(2026, 8, 20, 18, 0))
                        .build()));
        when(workerMapper.countShareableWorkplaces(any(ShareableWorkplaceListQuery.class)))
                .thenReturn(1L);

        PageResponse<ShareableWorkplaceListItemResponse> response =
                service.shareableWorkplaces(worker(), 0, 20);

        assertEquals(1, response.getContent().size());
        ShareableWorkplaceListItemResponse item = response.getContent().get(0);
        assertEquals(9L, item.getWorkplaceId());
        assertEquals("강남점", item.getWorkplaceName());
        assertEquals("김대표", item.getOwnerName());
        assertEquals(
                ApiTimes.toInstant(LocalDateTime.of(2026, 8, 20, 10, 0)), item.getStartsAt());
        assertEquals(
                ApiTimes.toInstant(LocalDateTime.of(2026, 8, 20, 18, 0)), item.getEndsAt());
    }

    private AuthPrincipal worker() {
        return new AuthPrincipal(WORKER_ID, UserRole.WORKER, "근로자");
    }

    private AuthPrincipal owner() {
        return new AuthPrincipal(1L, UserRole.OWNER, "사업주");
    }
}
