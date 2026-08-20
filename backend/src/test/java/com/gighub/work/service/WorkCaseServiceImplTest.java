package com.gighub.work.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.badge.service.BadgeApplicationService;
import com.gighub.badge.service.result.BadgeCalculationResult;
import com.gighub.common.api.ApiTimes;
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
import com.gighub.invitation.mapper.InvitationMapper;
import com.gighub.work.mapper.param.WorkCaseInsertParam;
import com.gighub.work.mapper.param.WorkCaseListQuery;
import com.gighub.work.mapper.param.WorkCaseTermsUpdateParam;
import com.gighub.work.mapper.result.AttendanceSummaryRow;
import com.gighub.work.mapper.result.ContractDetailRow;
import com.gighub.work.mapper.result.OwnedWorkplaceSnapshotRow;
import com.gighub.work.mapper.result.SettlementSummaryRow;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkCaseServiceImplTest {

    private static final Long OWNER_ID = 7L;
    private static final Long WORKPLACE_ID = 5L;
    private static final Long WORK_CASE_ID = 101L;

    private final WorkCaseMapper workCaseMapper = mock(WorkCaseMapper.class);
    private final InvitationMapper invitationMapper = mock(InvitationMapper.class);
    private final BadgeApplicationService badgeApplicationService = mock(BadgeApplicationService.class);
    private final WorkCaseServiceImpl service = new WorkCaseServiceImpl(
            workCaseMapper, invitationMapper, badgeApplicationService);

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

    /**
     * SPEC-413-01 — 자정을 넘기는 야간 근무는 종료를 근무일 <b>다음 날</b>에 저장한다.
     * workDate 는 근무가 시작하는 날이라는 뜻을 유지한다.
     */
    @Test
    void createStoresOvernightEndOnTheNextDay() {
        when(workCaseMapper.findOwnedActiveWorkplace(WORKPLACE_ID, OWNER_ID))
                .thenReturn(snapshot());
        doAnswer(invocation -> {
            invocation.getArgument(0, WorkCaseInsertParam.class).setWorkCaseId(WORK_CASE_ID);
            return 1;
        }).when(workCaseMapper).insert(any(WorkCaseInsertParam.class));

        service.create(owner(), createCommandWithTimes(LocalTime.of(23, 0), LocalTime.of(1, 0)));

        ArgumentCaptor<WorkCaseInsertParam> captor = ArgumentCaptor.forClass(WorkCaseInsertParam.class);
        verify(workCaseMapper).insert(captor.capture());

        assertEquals(LocalDateTime.of(2026, 8, 10, 23, 0), captor.getValue().getStartsAt());
        assertEquals(LocalDateTime.of(2026, 8, 11, 1, 0), captor.getValue().getEndsAt());
    }

    /**
     * 자정 넘김을 허용하면 순서 검증이 잡아 주던 오타를 길이 상한이 대신 잡는다.
     * {@code 09:00~09:00} 은 0분이 아니라 24시간으로 해석되어 여기서 걸린다.
     *
     * <p>사업장 조회를 stub 하지 않는다. 상한 검증이 그보다 앞서므로 stub 을 세우면
     * "조회 뒤에 검증해도 통과하는" 테스트가 된다 — 조회는 실제로 일어나면 안 된다.</p>
     *
     * <p>거절은 {@code endTime} 필드 오류를 함께 실어야 한다. 프론트가 같은 상한을 먼저
     * 걸러 주는 동안에는 드러나지 않지만, 두 상한이 어긋나면 사용자가 마주치는 것이
     * 정확히 이 경로다. 필드가 비면 화면은 무엇이 문제인지 알려주지 못한다.</p>
     */
    @Test
    void createRejectsWorkPeriodLongerThanTheCap() {
        ValidationException equalTimes = assertThrows(ValidationException.class, () -> service.create(
                owner(), createCommandWithTimes(LocalTime.of(9, 0), LocalTime.of(9, 0))));
        // 16시간 1분
        ValidationException justOverCap = assertThrows(ValidationException.class, () -> service.create(
                owner(), createCommandWithTimes(LocalTime.of(20, 0), LocalTime.of(12, 1))));

        assertTrue(equalTimes.getMessage().contains("16시간"), equalTimes.getMessage());
        assertEquals(1, justOverCap.getFieldErrors().size());
        assertEquals("endTime", justOverCap.getFieldErrors().get(0).getField());
        assertTrue(
                justOverCap.getFieldErrors().get(0).getReason().contains("16시간"),
                justOverCap.getFieldErrors().get(0).getReason());

        verifyNoInteractions(invitationMapper);
        verify(workCaseMapper, never()).findOwnedActiveWorkplace(any(), any());
        verify(workCaseMapper, never()).insert(any());
    }

    /**
     * 휴게 시간이 근무 시간을 넘으면 등록에서 막는다.
     *
     * <p>{@code breakMinutes}의 Bean Validation 상한은 컬럼 표현 범위(65535)뿐이라 이 값이
     * 그대로 저장됐다. 그 뒤 초대 수락 트랜잭션에서 {@code ContractSnapshot}이 거절해
     * 계약·에스크로가 도는 도중 500이 된다. 자정 넘김 이전에도 있던 구멍이지만, 근무 길이
     * 상한이 16시간으로 좁아지면서 통과 가능한 휴게 시간 범위와의 간극이 커졌다.</p>
     */
    @Test
    void createRejectsBreakLongerThanTheWorkPeriod() {
        // 23:00 ~ 다음 날 01:00 = 120분 근무인데 휴게 180분
        WorkCaseCreateCommand command = WorkCaseCreateCommand.builder()
                .workplaceId(WORKPLACE_ID)
                .title("야간 마감")
                .workDate(LocalDate.of(2026, 8, 10))
                .startTime(LocalTime.of(23, 0))
                .endTime(LocalTime.of(1, 0))
                .breakMinutes(180)
                .breakPaid(false)
                .dailyWage(120_000L)
                .build();

        ValidationException thrown =
                assertThrows(ValidationException.class, () -> service.create(owner(), command));

        assertEquals("breakMinutes", thrown.getFieldErrors().get(0).getField());
        assertTrue(thrown.getMessage().contains("120"), thrown.getMessage());
        verify(workCaseMapper, never()).insert(any());
    }

    /** 무급 휴게가 근무 전체와 같으면 차감 분모가 0이므로 저장 전에 거절한다. */
    @Test
    void createRejectsUnpaidBreakEqualToTheWorkPeriod() {
        WorkCaseCreateCommand command = WorkCaseCreateCommand.builder()
                .workplaceId(WORKPLACE_ID)
                .title("야간 마감")
                .workDate(LocalDate.of(2026, 8, 10))
                .startTime(LocalTime.of(23, 0))
                .endTime(LocalTime.of(1, 0))
                .breakMinutes(120)
                .breakPaid(false)
                .dailyWage(120_000L)
                .build();

        ValidationException thrown =
                assertThrows(ValidationException.class, () -> service.create(owner(), command));

        assertEquals("breakMinutes", thrown.getFieldErrors().get(0).getField());
        assertTrue(thrown.getMessage().contains("보다 짧아야"), thrown.getMessage());
        verify(workCaseMapper, never()).insert(any());
    }

    /** 유급 휴게는 차감 분모에서 빼지 않으므로 근무 전체와 같은 경계도 허용한다. */
    @Test
    void createAcceptsPaidBreakEqualToTheWorkPeriod() {
        when(workCaseMapper.findOwnedActiveWorkplace(WORKPLACE_ID, OWNER_ID))
                .thenReturn(snapshot());
        doAnswer(invocation -> {
            invocation.getArgument(0, WorkCaseInsertParam.class).setWorkCaseId(WORK_CASE_ID);
            return 1;
        }).when(workCaseMapper).insert(any(WorkCaseInsertParam.class));

        WorkCaseCreateCommand command = WorkCaseCreateCommand.builder()
                .workplaceId(WORKPLACE_ID)
                .title("야간 마감")
                .workDate(LocalDate.of(2026, 8, 10))
                .startTime(LocalTime.of(23, 0))
                .endTime(LocalTime.of(1, 0))
                .breakMinutes(120)
                .breakPaid(true)
                .dailyWage(120_000L)
                .build();

        service.create(owner(), command);

        verify(workCaseMapper).insert(any(WorkCaseInsertParam.class));
    }

    /** 경계값 자체는 저장된다 — 상한을 벗어난 입력만 거절해야 한다. */
    @Test
    void createAcceptsWorkPeriodExactlyAtTheCap() {
        when(workCaseMapper.findOwnedActiveWorkplace(WORKPLACE_ID, OWNER_ID))
                .thenReturn(snapshot());
        doAnswer(invocation -> {
            invocation.getArgument(0, WorkCaseInsertParam.class).setWorkCaseId(WORK_CASE_ID);
            return 1;
        }).when(workCaseMapper).insert(any(WorkCaseInsertParam.class));

        // 20:00 ~ 다음 날 12:00 = 정확히 16시간
        service.create(owner(), createCommandWithTimes(LocalTime.of(20, 0), LocalTime.of(12, 0)));

        ArgumentCaptor<WorkCaseInsertParam> captor = ArgumentCaptor.forClass(WorkCaseInsertParam.class);
        verify(workCaseMapper).insert(captor.capture());
        assertEquals(LocalDateTime.of(2026, 8, 11, 12, 0), captor.getValue().getEndsAt());
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
        verify(invitationMapper).revokePendingByWorkCaseIdNow(WORK_CASE_ID);

        assertEquals(WORK_CASE_ID, captor.getValue().getWorkCaseId());
    }

    /**
     * SPEC-413-01 — 조건 수정도 등록과 같은 결합·상한 규칙을 쓴다.
     * 한쪽에만 적용하면 등록으로 막힌 값이 수정으로 들어온다.
     */
    @Test
    void updateAppliesTheSameOvernightAndCapRules() {
        when(workCaseMapper.lockById(WORK_CASE_ID))
                .thenReturn(lockRow(OWNER_ID, WorkCaseStatus.DRAFT));
        when(workCaseMapper.updateDraftTerms(any())).thenReturn(1);

        service.update(owner(), updateCommandWithTimes(LocalTime.of(23, 0), LocalTime.of(1, 0)));

        ArgumentCaptor<WorkCaseTermsUpdateParam> captor =
                ArgumentCaptor.forClass(WorkCaseTermsUpdateParam.class);
        verify(workCaseMapper).updateDraftTerms(captor.capture());
        assertEquals(LocalDateTime.of(2026, 8, 11, 1, 0), captor.getValue().getEndsAt());

        assertThrows(ValidationException.class, () -> service.update(
                owner(), updateCommandWithTimes(LocalTime.of(9, 0), LocalTime.of(9, 0))));
        // 거절된 요청은 갱신을 남기지 않는다 — 위 성공 1회가 전부다.
        verify(workCaseMapper, times(1)).updateDraftTerms(any());
    }

    @Test
    void updateFailsClosedWhenExpectedStateUpdateAffectsNoRow() {
        when(workCaseMapper.lockById(WORK_CASE_ID))
                .thenReturn(lockRow(OWNER_ID, WorkCaseStatus.DRAFT));
        when(workCaseMapper.updateDraftTerms(any())).thenReturn(0);

        assertThrows(
                IllegalStateException.class,
                () -> service.update(owner(), validUpdateCommand()));

        verify(invitationMapper, never()).revokePendingByWorkCaseIdNow(anyLong());
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
        verify(invitationMapper, never()).revokePendingByWorkCaseIdNow(anyLong());
    }

    @Test
    void deleteCancelsDraftWithInvitationHistory() {
        when(workCaseMapper.lockById(WORK_CASE_ID))
                .thenReturn(lockRow(OWNER_ID, WorkCaseStatus.DRAFT));
        when(workCaseMapper.countInvitations(WORK_CASE_ID)).thenReturn(2);
        when(workCaseMapper.cancelDraft(WORK_CASE_ID)).thenReturn(1);

        service.delete(owner(), WORK_CASE_ID);

        verify(invitationMapper).revokePendingByWorkCaseIdNow(WORK_CASE_ID);
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
        when(badgeApplicationService.recalculate(workerId))
                .thenReturn(BadgeCalculationResult.of("TRUST_WORKER", 2, 10, 9, 5, 90, 0, 100, 6));

        WorkCaseDetailResponse response = service.detail(
                new AuthPrincipal(workerId, UserRole.WORKER, "이알바"), WORK_CASE_ID);

        assertEquals(WORK_CASE_ID, response.getWorkCaseId());
        assertEquals("TRUST_WORKER", response.getWorker().getBadge().getBadgeType());
        assertEquals(2, response.getWorker().getBadge().getLevel());
    }

    /**
     * 초대 응답의 {@code ownerBadge}와 같은 계약이다 — 0단계는 활성 Badge 없음과 같은
     * {@code null}이며, 빈 객체나 0단계 값으로 채우지 않는다.
     */
    @Test
    void detailReturnsNullWorkerBadgeWhenLevelIsZero() {
        Long workerId = 42L;
        when(workCaseMapper.findDetailRow(WORK_CASE_ID)).thenReturn(detailRow(OWNER_ID, workerId));
        when(workCaseMapper.findAttendanceTimestamps(WORK_CASE_ID)).thenReturn(emptyAttendance());
        when(badgeApplicationService.recalculate(workerId))
                .thenReturn(BadgeCalculationResult.of("TRUST_WORKER", 0, 3, 1, 5, 90, 4, 100, 5));

        WorkCaseDetailResponse response = service.detail(
                new AuthPrincipal(workerId, UserRole.WORKER, "이알바"), WORK_CASE_ID);

        assertNull(response.getWorker().getBadge());
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
    void detailReturnsTheStoredSettlementCalculationSnapshot() {
        LocalDateTime calculatedAt = LocalDateTime.of(2026, 8, 20, 18, 5);
        when(workCaseMapper.findDetailRow(WORK_CASE_ID)).thenReturn(detailRow(OWNER_ID, null));
        when(workCaseMapper.findAttendanceTimestamps(WORK_CASE_ID)).thenReturn(emptyAttendance());
        when(workCaseMapper.findSettlement(WORK_CASE_ID)).thenReturn(SettlementSummaryRow.builder()
                .status("COMPLETED")
                .amount(120_000L)
                .workerPaidAmount(90_000L)
                .ownerRefundAmount(30_000L)
                .deductionBaseMinutes(420L)
                .lateMinutes(105L)
                .earlyLeaveMinutes(0L)
                .calculationReason("CHECKED_OUT")
                .calculationVersion("ATTENDANCE_V1")
                .calculatedAt(calculatedAt)
                .completedAt(calculatedAt.plusMinutes(1))
                .build());

        WorkCaseDetailResponse.SettlementSummary settlement =
                service.detail(owner(), WORK_CASE_ID).getSettlement();

        assertNotNull(settlement);
        assertEquals(120_000L, settlement.getAmount());
        assertEquals(120_000L, settlement.getOriginalEscrowAmount());
        assertEquals(90_000L, settlement.getWorkerPaidAmount());
        assertEquals(30_000L, settlement.getOwnerRefundAmount());
        assertEquals(30_000L, settlement.getDeductionAmount());
        assertEquals(420L, settlement.getDeductionBaseMinutes());
        assertEquals(105L, settlement.getLateMinutes());
        assertEquals(0L, settlement.getEarlyLeaveMinutes());
        assertEquals("CHECKED_OUT", settlement.getCalculationReason());
        assertEquals("ATTENDANCE_V1", settlement.getCalculationVersion());
        assertEquals(ApiTimes.toInstant(calculatedAt), settlement.getCalculatedAt());
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

    /**
     * 보존 만료 파기(DOC-012)로 문서가 DELETED가 된 계약은 손상이 아니라 정상 정책 결과이므로
     * 500을 던지지 않고, documentId만 감춰 클라이언트가 이미 사라진 파일을 다시 요청하지
     * 않게 한다.
     */
    @Test
    void detailHidesDocumentIdForARetentionPurgedContract() {
        when(workCaseMapper.findDetailRow(WORK_CASE_ID)).thenReturn(detailRow(OWNER_ID, null));
        when(workCaseMapper.findContractDetail(WORK_CASE_ID)).thenReturn(ContractDetailRow.builder()
                .contractId(31L)
                .documentId(77L)
                .documentStatus("DELETED")
                .sourceTermsVersion(3)
                .acceptedAt(LocalDateTime.of(2026, 8, 10, 4, 0))
                .build());
        when(workCaseMapper.findAttendanceTimestamps(WORK_CASE_ID)).thenReturn(emptyAttendance());

        WorkCaseDetailResponse response = service.detail(owner(), WORK_CASE_ID);

        assertNotNull(response.getContract());
        assertNull(response.getContract().getDocumentId());
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
        return createCommandWithTimes(LocalTime.of(9, 0), LocalTime.of(18, 0));
    }

    private WorkCaseCreateCommand createCommandWithTimes(LocalTime startTime, LocalTime endTime) {
        return WorkCaseCreateCommand.builder()
                .workplaceId(WORKPLACE_ID)
                .title("주말 홀 서빙")
                .workDate(LocalDate.of(2026, 8, 10))
                .startTime(startTime)
                .endTime(endTime)
                .breakMinutes(60)
                .breakPaid(false)
                .dailyWage(120_000L)
                .build();
    }

    private WorkCaseUpdateCommand validUpdateCommand() {
        return updateCommandWithTimes(LocalTime.of(10, 0), LocalTime.of(19, 0));
    }

    private WorkCaseUpdateCommand updateCommandWithTimes(LocalTime startTime, LocalTime endTime) {
        return WorkCaseUpdateCommand.builder()
                .workCaseId(WORK_CASE_ID)
                .title("주말 홀 서빙(수정)")
                .workDate(LocalDate.of(2026, 8, 10))
                .startTime(startTime)
                .endTime(endTime)
                .breakMinutes(30)
                .breakPaid(true)
                .dailyWage(130_000L)
                .build();
    }
}
