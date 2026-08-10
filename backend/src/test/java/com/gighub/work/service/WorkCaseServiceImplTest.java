package com.gighub.work.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.ResourceNotFoundException;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.common.exception.ValidationException;
import com.gighub.common.exception.WorkCaseLockedException;
import com.gighub.common.api.PageResponse;
import com.gighub.member.domain.UserRole;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.dto.WorkCaseDetailResponse;
import com.gighub.work.dto.WorkCaseListItemResponse;
import com.gighub.work.dto.WorkCaseSummaryResponse;
import com.gighub.work.mapper.WorkCaseMapper;
import com.gighub.work.mapper.param.WorkCaseInsertParam;
import com.gighub.work.mapper.param.WorkCaseListQuery;
import com.gighub.work.mapper.param.WorkCaseTermsUpdateParam;
import com.gighub.work.mapper.result.AttendanceSummaryRow;
import com.gighub.work.mapper.result.ContractDetailRow;
import com.gighub.work.mapper.result.OwnedWorkplaceSnapshotRow;
import com.gighub.work.mapper.result.WorkCaseDetailRow;
import com.gighub.work.mapper.result.WorkCaseListRow;
import com.gighub.work.mapper.result.WorkCaseLockRow;
import com.gighub.work.mapper.result.WorkCaseStatusCountRow;
import com.gighub.work.service.command.WorkCaseCreateCommand;
import com.gighub.work.service.command.WorkCaseUpdateCommand;
import com.gighub.work.service.impl.WorkCaseServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkCaseServiceImplTest {

    private static final Long OWNER_ID = 7L;
    private static final Long WORKPLACE_ID = 5L;
    private static final Long WORK_CASE_ID = 101L;

    private final WorkCaseMapper workCaseMapper = mock(WorkCaseMapper.class);
    private final WorkCaseServiceImpl service = new WorkCaseServiceImpl(workCaseMapper);

    // ---------- create ----------

    @Test
    void createRejectsNonOwner() {
        assertThrows(
                RoleMismatchException.class,
                () -> service.create(worker(), validCreateCommand()));

        verifyNoInteractions(workCaseMapper);
    }

    @Test
    void createRejectsWhenWorkplaceIsNotOwnedActive() {
        when(workCaseMapper.findOwnedActiveWorkplace(WORKPLACE_ID, OWNER_ID)).thenReturn(null);

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.create(owner(), validCreateCommand()));

        verify(workCaseMapper, never()).insert(any());
    }

    @Test
    void createRejectsEndTimeNotAfterStartTime() {
        when(workCaseMapper.findOwnedActiveWorkplace(WORKPLACE_ID, OWNER_ID))
                .thenReturn(snapshot());

        WorkCaseCreateCommand command = WorkCaseCreateCommand.builder()
                .workplaceId(WORKPLACE_ID)
                .title("주말 홀 서빙")
                .workDate(LocalDate.of(2026, 8, 10))
                .startTime(LocalTime.of(18, 0))
                .endTime(LocalTime.of(9, 0))
                .breakMinutes(60)
                .breakPaid(false)
                .dailyWage(120_000L)
                .build();

        assertThrows(ValidationException.class, () -> service.create(owner(), command));

        verify(workCaseMapper, never()).insert(any());
    }

    @Test
    void createStoresCombinedAddressAndServerOwnedColumns() {
        when(workCaseMapper.findOwnedActiveWorkplace(WORKPLACE_ID, OWNER_ID))
                .thenReturn(snapshot());
        doAnswer(invocation -> {
            invocation.getArgument(0, WorkCaseInsertParam.class).setWorkCaseId(WORK_CASE_ID);
            return 1;
        }).when(workCaseMapper).insert(any(WorkCaseInsertParam.class));

        Long workCaseId = service.create(owner(), validCreateCommand());

        assertEquals(WORK_CASE_ID, workCaseId);

        ArgumentCaptor<WorkCaseInsertParam> captor = ArgumentCaptor.forClass(WorkCaseInsertParam.class);
        verify(workCaseMapper).insert(captor.capture());

        WorkCaseInsertParam param = captor.getValue();
        assertEquals(OWNER_ID, param.getEmployerId());
        assertEquals(WORKPLACE_ID, param.getWorkplaceId());
        assertEquals("서울 강남구 테헤란로 1 2층", param.getWorkplaceAddress());
        assertEquals(LocalDateTime.of(2026, 8, 10, 9, 0), param.getStartsAt());
        assertEquals(LocalDateTime.of(2026, 8, 10, 18, 0), param.getEndsAt());
    }

    // ---------- update ----------

    @Test
    void updateRejectsWhenWorkCaseNotOwnedByPrincipal() {
        when(workCaseMapper.lockById(WORK_CASE_ID)).thenReturn(lockRow(99L, WorkCaseStatus.DRAFT));

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.update(owner(), validUpdateCommand()));

        verify(workCaseMapper, never()).updateDraftTerms(any());
    }

    @Test
    void updateRejectsWhenNotDraft() {
        when(workCaseMapper.lockById(WORK_CASE_ID))
                .thenReturn(lockRow(OWNER_ID, WorkCaseStatus.ACCEPTED));

        assertThrows(
                WorkCaseLockedException.class,
                () -> service.update(owner(), validUpdateCommand()));

        verify(workCaseMapper, never()).updateDraftTerms(any());
    }

    @Test
    void updateBumpsVersionAndRevokesPendingInvitations() {
        when(workCaseMapper.lockById(WORK_CASE_ID))
                .thenReturn(lockRow(OWNER_ID, WorkCaseStatus.DRAFT));
        when(workCaseMapper.updateDraftTerms(any())).thenReturn(1);

        service.update(owner(), validUpdateCommand());

        ArgumentCaptor<WorkCaseTermsUpdateParam> captor =
                ArgumentCaptor.forClass(WorkCaseTermsUpdateParam.class);
        verify(workCaseMapper).updateDraftTerms(captor.capture());
        verify(workCaseMapper).revokePendingInvitations(WORK_CASE_ID);

        assertEquals(WORK_CASE_ID, captor.getValue().getWorkCaseId());
    }

    @Test
    void updateFailsClosedWhenExpectedStateUpdateAffectsNoRow() {
        when(workCaseMapper.lockById(WORK_CASE_ID))
                .thenReturn(lockRow(OWNER_ID, WorkCaseStatus.DRAFT));
        when(workCaseMapper.updateDraftTerms(any())).thenReturn(0);

        assertThrows(
                IllegalStateException.class,
                () -> service.update(owner(), validUpdateCommand()));

        verify(workCaseMapper, never()).revokePendingInvitations(anyLong());
    }

    // ---------- delete ----------

    @Test
    void deleteHardDeletesDraftWithoutInvitationHistory() {
        when(workCaseMapper.lockById(WORK_CASE_ID))
                .thenReturn(lockRow(OWNER_ID, WorkCaseStatus.DRAFT));
        when(workCaseMapper.countInvitations(WORK_CASE_ID)).thenReturn(0);
        when(workCaseMapper.deleteDraft(WORK_CASE_ID)).thenReturn(1);

        service.delete(owner(), WORK_CASE_ID);

        verify(workCaseMapper).deleteDraft(WORK_CASE_ID);
        verify(workCaseMapper, never()).cancelDraft(anyLong());
        verify(workCaseMapper, never()).revokePendingInvitations(anyLong());
    }

    @Test
    void deleteCancelsDraftWithInvitationHistory() {
        when(workCaseMapper.lockById(WORK_CASE_ID))
                .thenReturn(lockRow(OWNER_ID, WorkCaseStatus.DRAFT));
        when(workCaseMapper.countInvitations(WORK_CASE_ID)).thenReturn(2);
        when(workCaseMapper.cancelDraft(WORK_CASE_ID)).thenReturn(1);

        service.delete(owner(), WORK_CASE_ID);

        verify(workCaseMapper).revokePendingInvitations(WORK_CASE_ID);
        verify(workCaseMapper).cancelDraft(WORK_CASE_ID);
        verify(workCaseMapper, never()).deleteDraft(anyLong());
    }

    @Test
    void deleteFailsClosedWhenExpectedStateDeleteAffectsNoRow() {
        when(workCaseMapper.lockById(WORK_CASE_ID))
                .thenReturn(lockRow(OWNER_ID, WorkCaseStatus.DRAFT));
        when(workCaseMapper.countInvitations(WORK_CASE_ID)).thenReturn(0);
        when(workCaseMapper.deleteDraft(WORK_CASE_ID)).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.delete(owner(), WORK_CASE_ID));
    }

    @Test
    void cancelFailsClosedWhenExpectedStateUpdateAffectsNoRow() {
        when(workCaseMapper.lockById(WORK_CASE_ID))
                .thenReturn(lockRow(OWNER_ID, WorkCaseStatus.DRAFT));
        when(workCaseMapper.countInvitations(WORK_CASE_ID)).thenReturn(1);
        when(workCaseMapper.cancelDraft(WORK_CASE_ID)).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.delete(owner(), WORK_CASE_ID));
    }

    @Test
    void deleteConvertsForeignKeyRestrictIntoWorkCaseLocked() {
        when(workCaseMapper.lockById(WORK_CASE_ID))
                .thenReturn(lockRow(OWNER_ID, WorkCaseStatus.DRAFT));
        when(workCaseMapper.countInvitations(WORK_CASE_ID)).thenReturn(0);
        doThrow(new DataIntegrityViolationException("fk violation"))
                .when(workCaseMapper).deleteDraft(WORK_CASE_ID);

        assertThrows(WorkCaseLockedException.class, () -> service.delete(owner(), WORK_CASE_ID));
    }

    @Test
    void deleteRejectsWhenNotDraft() {
        when(workCaseMapper.lockById(WORK_CASE_ID))
                .thenReturn(lockRow(OWNER_ID, WorkCaseStatus.CANCELED));

        assertThrows(WorkCaseLockedException.class, () -> service.delete(owner(), WORK_CASE_ID));

        verify(workCaseMapper, never()).deleteDraft(anyLong());
        verify(workCaseMapper, never()).cancelDraft(anyLong());
    }

    // ---------- summary ----------

    @Test
    void summaryRejectsNonOwner() {
        assertThrows(
                RoleMismatchException.class,
                () -> service.summary(worker(), WORKPLACE_ID));

        verifyNoInteractions(workCaseMapper);
    }

    @Test
    void summaryRejectsUnownedWorkplace() {
        when(workCaseMapper.existsOwnedManageableWorkplace(WORKPLACE_ID, OWNER_ID))
                .thenReturn(false);

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.summary(owner(), WORKPLACE_ID));

        verify(workCaseMapper, never()).countByStatus(any(), any());
    }

    @Test
    void summaryFillsMissingStatusesWithZero() {
        when(workCaseMapper.existsOwnedManageableWorkplace(WORKPLACE_ID, OWNER_ID))
                .thenReturn(true);
        when(workCaseMapper.countByStatus(WORKPLACE_ID, OWNER_ID)).thenReturn(List.of(
                WorkCaseStatusCountRow.builder().status(WorkCaseStatus.DRAFT).caseCount(2L).build()));

        WorkCaseSummaryResponse response = service.summary(owner(), WORKPLACE_ID);

        assertEquals(2, response.getDraft());
        assertEquals(0, response.getCompleted());
    }

    // ---------- list ----------

    @Test
    void listRejectsNonOwner() {
        assertThrows(
                RoleMismatchException.class,
                () -> service.list(worker(), WORKPLACE_ID, null, null, null, null, 0, 20));

        verifyNoInteractions(workCaseMapper);
    }

    @Test
    void listRejectsInvalidPageBeforeCheckingOwnership() {
        assertThrows(
                ValidationException.class,
                () -> service.list(owner(), WORKPLACE_ID, null, null, null, null, -1, 20));

        verify(workCaseMapper, never()).existsOwnedManageableWorkplace(any(), any());
    }

    @Test
    void listRejectsUnownedWorkplace() {
        when(workCaseMapper.existsOwnedManageableWorkplace(WORKPLACE_ID, OWNER_ID))
                .thenReturn(false);

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.list(owner(), WORKPLACE_ID, null, null, null, null, 0, 20));

        verify(workCaseMapper, never()).countByFilters(any());
        verify(workCaseMapper, never()).findPageByFilters(any());
    }

    @Test
    void listRejectsFromAfterTo() {
        when(workCaseMapper.existsOwnedManageableWorkplace(WORKPLACE_ID, OWNER_ID))
                .thenReturn(true);

        assertThrows(
                ValidationException.class,
                () -> service.list(
                        owner(), WORKPLACE_ID, null, null,
                        LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 10), 0, 20));

        verify(workCaseMapper, never()).countByFilters(any());
    }

    @Test
    void listTrimsBlankKeywordToNull() {
        when(workCaseMapper.existsOwnedManageableWorkplace(WORKPLACE_ID, OWNER_ID))
                .thenReturn(true);
        when(workCaseMapper.countByFilters(any())).thenReturn(0L);
        when(workCaseMapper.findPageByFilters(any())).thenReturn(List.of());

        service.list(owner(), WORKPLACE_ID, "   ", null, null, null, 0, 20);

        ArgumentCaptor<WorkCaseListQuery> captor = ArgumentCaptor.forClass(WorkCaseListQuery.class);
        verify(workCaseMapper).countByFilters(captor.capture());
        assertEquals(null, captor.getValue().getKeyword());
    }

    @Test
    void listReturnsMappedContentAndPageMetadata() {
        when(workCaseMapper.existsOwnedManageableWorkplace(WORKPLACE_ID, OWNER_ID))
                .thenReturn(true);
        when(workCaseMapper.countByFilters(any())).thenReturn(1L);
        when(workCaseMapper.findPageByFilters(any())).thenReturn(List.of(
                WorkCaseListRow.builder()
                        .workCaseId(WORK_CASE_ID)
                        .title("주말 홀 서빙")
                        .startsAt(LocalDateTime.of(2026, 8, 10, 9, 0))
                        .endsAt(LocalDateTime.of(2026, 8, 10, 18, 0))
                        .dailyWage(120_000L)
                        .status(WorkCaseStatus.DRAFT)
                        .workerId(null)
                        .workerName(null)
                        .build()));

        PageResponse<WorkCaseListItemResponse> page =
                service.list(owner(), WORKPLACE_ID, "서빙", WorkCaseStatus.DRAFT, null, null, 0, 20);

        assertEquals(1, page.getContent().size());
        assertEquals(WORK_CASE_ID, page.getContent().get(0).getWorkCaseId());
        assertEquals(1, page.getPage().getTotalElements());

        ArgumentCaptor<WorkCaseListQuery> captor = ArgumentCaptor.forClass(WorkCaseListQuery.class);
        verify(workCaseMapper).findPageByFilters(captor.capture());
        assertEquals("서빙", captor.getValue().getKeyword());
        assertEquals(WorkCaseStatus.DRAFT, captor.getValue().getStatus());
        assertEquals(0L, captor.getValue().getOffset());
    }

    // ---------- detail ----------

    @Test
    void detailRejectsMissingWorkCase() {
        when(workCaseMapper.findDetailRow(WORK_CASE_ID)).thenReturn(null);

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.detail(owner(), WORK_CASE_ID));
    }

    @Test
    void detailRejectsThirdParty() {
        when(workCaseMapper.findDetailRow(WORK_CASE_ID)).thenReturn(detailRow(OWNER_ID, 99L));

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.detail(new AuthPrincipal(1234L, UserRole.WORKER, "제3자"), WORK_CASE_ID));
    }

    @Test
    void detailAllowsOwner() {
        when(workCaseMapper.findDetailRow(WORK_CASE_ID)).thenReturn(detailRow(OWNER_ID, null));
        when(workCaseMapper.findAttendanceTimestamps(WORK_CASE_ID)).thenReturn(emptyAttendance());

        WorkCaseDetailResponse response = service.detail(owner(), WORK_CASE_ID);

        assertEquals(WORK_CASE_ID, response.getWorkCaseId());
    }

    /**
     * 근태 기록이 없으면 Mapper가 {@code null}을 돌려준다.
     *
     * <p>집계 SQL이라 행은 있지만 두 컬럼이 모두 {@code NULL}이고, MyBatis 생성자 resultMap은
     * 그런 행을 {@code null} 객체로 매핑한다. 위 테스트들이 쓰는 빈 객체 stub은 이 실제 동작과
     * 달라 출근 전 근무의 상세 조회 실패를 잡지 못했다.</p>
     */
    @Test
    void detailReturnsEmptyAttendanceWhenNoRecordsExist() {
        when(workCaseMapper.findDetailRow(WORK_CASE_ID)).thenReturn(detailRow(OWNER_ID, null));
        when(workCaseMapper.findAttendanceTimestamps(WORK_CASE_ID)).thenReturn(null);

        WorkCaseDetailResponse response = service.detail(owner(), WORK_CASE_ID);

        assertNotNull(response.getAttendance());
        assertNull(response.getAttendance().getCheckedInAt());
        assertNull(response.getAttendance().getCheckedOutAt());
    }

    @Test
    void detailAllowsMatchedWorker() {
        Long workerId = 42L;
        when(workCaseMapper.findDetailRow(WORK_CASE_ID)).thenReturn(detailRow(OWNER_ID, workerId));
        when(workCaseMapper.findAttendanceTimestamps(WORK_CASE_ID)).thenReturn(emptyAttendance());

        WorkCaseDetailResponse response = service.detail(
                new AuthPrincipal(workerId, UserRole.WORKER, "이알바"), WORK_CASE_ID);

        assertEquals(WORK_CASE_ID, response.getWorkCaseId());
    }

    @Test
    void detailReturnsNullNestedObjectsWhenNoAggregateExists() {
        when(workCaseMapper.findDetailRow(WORK_CASE_ID)).thenReturn(detailRow(OWNER_ID, null));
        when(workCaseMapper.findLatestInvitation(WORK_CASE_ID)).thenReturn(null);
        when(workCaseMapper.findContractDetail(WORK_CASE_ID)).thenReturn(null);
        when(workCaseMapper.findEscrow(WORK_CASE_ID)).thenReturn(null);
        when(workCaseMapper.findSettlement(WORK_CASE_ID)).thenReturn(null);
        when(workCaseMapper.findAttendanceTimestamps(WORK_CASE_ID)).thenReturn(emptyAttendance());

        WorkCaseDetailResponse response = service.detail(owner(), WORK_CASE_ID);

        assertNull(response.getWorker());
        assertNull(response.getLatestInvitation());
        assertNull(response.getContract());
        assertNull(response.getEscrow());
        assertNull(response.getSettlement());
        assertNotNull(response.getAttendance(), "attendance는 항상 객체여야 합니다.");
        assertNull(response.getAttendance().getCheckedInAt());
    }

    @Test
    void detailRejectsContractWithoutLinkedDocument() {
        when(workCaseMapper.findDetailRow(WORK_CASE_ID)).thenReturn(detailRow(OWNER_ID, null));
        when(workCaseMapper.findContractDetail(WORK_CASE_ID)).thenReturn(ContractDetailRow.builder()
                .contractId(31L)
                .documentId(null)
                .sourceTermsVersion(3)
                .acceptedAt(LocalDateTime.of(2026, 8, 10, 4, 0))
                .build());

        assertThrows(IllegalStateException.class, () -> service.detail(owner(), WORK_CASE_ID));
    }

    // ---------- fixtures ----------

    private AuthPrincipal owner() {
        return new AuthPrincipal(OWNER_ID, UserRole.OWNER, "김사장");
    }

    private AuthPrincipal worker() {
        return new AuthPrincipal(OWNER_ID, UserRole.WORKER, "이알바");
    }

    private OwnedWorkplaceSnapshotRow snapshot() {
        return OwnedWorkplaceSnapshotRow.builder()
                .workplaceId(WORKPLACE_ID)
                .workplaceName("강남점")
                .roadAddress("서울 강남구 테헤란로 1")
                .detailAddress("2층")
                .latitude(new BigDecimal("37.1234567"))
                .longitude(new BigDecimal("127.1234567"))
                .radiusMeters(new BigDecimal("100.00"))
                .build();
    }

    private AttendanceSummaryRow emptyAttendance() {
        return AttendanceSummaryRow.builder().checkedInAt(null).checkedOutAt(null).build();
    }

    private WorkCaseDetailRow detailRow(Long employerId, Long workerId) {
        return WorkCaseDetailRow.builder()
                .workCaseId(WORK_CASE_ID)
                .title("주말 홀 서빙")
                .startsAt(LocalDateTime.of(2026, 8, 20, 9, 0))
                .endsAt(LocalDateTime.of(2026, 8, 20, 18, 0))
                .breakMinutes(60)
                .breakPaid(false)
                .dailyWage(120_000L)
                .status(WorkCaseStatus.DRAFT)
                .termsVersion(1)
                .workplaceName("강남점")
                .workplaceAddress("서울 강남구 테헤란로 1 2층")
                .employerId(employerId)
                .workerId(workerId)
                .workerName(workerId == null ? null : "이알바")
                .build();
    }

    private WorkCaseLockRow lockRow(Long employerId, WorkCaseStatus status) {
        return WorkCaseLockRow.builder()
                .workCaseId(WORK_CASE_ID)
                .employerId(employerId)
                .workplaceId(WORKPLACE_ID)
                .workerId(null)
                .status(status)
                .termsVersion(1)
                .build();
    }

    private WorkCaseCreateCommand validCreateCommand() {
        return WorkCaseCreateCommand.builder()
                .workplaceId(WORKPLACE_ID)
                .title("주말 홀 서빙")
                .workDate(LocalDate.of(2026, 8, 10))
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .breakMinutes(60)
                .breakPaid(false)
                .dailyWage(120_000L)
                .build();
    }

    private WorkCaseUpdateCommand validUpdateCommand() {
        return WorkCaseUpdateCommand.builder()
                .workCaseId(WORK_CASE_ID)
                .title("주말 홀 서빙(수정)")
                .workDate(LocalDate.of(2026, 8, 10))
                .startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(19, 0))
                .breakMinutes(30)
                .breakPaid(true)
                .dailyWage(130_000L)
                .build();
    }
}
