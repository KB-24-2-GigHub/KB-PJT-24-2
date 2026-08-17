package com.gighub.settlement.service;

import com.gighub.common.api.PageResponse;
import com.gighub.common.exception.ResourceNotFoundException;
import com.gighub.member.domain.UserRole;
import com.gighub.notification.domain.NotificationType;
import com.gighub.notification.service.NotificationRecorder;
import com.gighub.notification.service.command.NotificationRecordCommand;
import com.gighub.settlement.domain.DisputeStatus;
import com.gighub.settlement.domain.SettlementStatus;
import com.gighub.settlement.dto.DisputeListItemResponse;
import com.gighub.settlement.dto.SettlementSnapshot;
import com.gighub.settlement.exception.DisputeAlreadyOpenException;
import com.gighub.settlement.exception.DisputeReviewUnavailableException;
import com.gighub.settlement.mapper.DisputeMapper;
import com.gighub.settlement.mapper.SettlementMapper;
import com.gighub.settlement.mapper.command.DisputeInsert;
import com.gighub.settlement.mapper.result.DisputeListRow;
import com.gighub.settlement.review.DisputeReviewExecutionStatus;
import com.gighub.settlement.review.DisputeReviewDecision;
import com.gighub.settlement.service.command.DisputeCreateCommand;
import com.gighub.settlement.service.impl.DisputeServiceImpl;
import com.gighub.work.contract.WorkCaseEscrowSnapshot;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.service.WorkSettlementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisputeServiceTest {

    private static final long WORK_CASE_ID = 11L;
    private static final long OWNER_ID = 21L;
    private static final long WORKER_ID = 22L;

    @Mock
    private WorkSettlementService workSettlementService;
    @Mock
    private SettlementMapper settlementMapper;
    @Mock
    private DisputeMapper disputeMapper;
    @Mock
    private DisputeReviewQueueService reviewQueueService;
    @Mock
    private NotificationRecorder notificationRecorder;
    @InjectMocks
    private DisputeServiceImpl disputeService;

    @Test
    void workerCreatesOpenDisputeAndScheduledSettlementMovesToHold() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(workCase(WorkCaseStatus.COMPLETED));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.SCHEDULED));
        when(disputeMapper.findOpenIdsForUpdate(WORK_CASE_ID)).thenReturn(List.of());
        doAnswer(invocation -> {
            DisputeInsert insert = invocation.getArgument(0);
            insert.setReportId(91L);
            return 1;
        }).when(disputeMapper).insertOpen(any());
        when(settlementMapper.transitionScheduledToOnHold(31L)).thenReturn(1);

        Long reportId = disputeService.create(command("  임금 확인  ", "  약정 일급이 미지급됐습니다.  "));

        assertEquals(91L, reportId);
        ArgumentCaptor<DisputeInsert> insert = ArgumentCaptor.forClass(DisputeInsert.class);
        verify(disputeMapper).insertOpen(insert.capture());
        assertEquals("임금 확인", insert.getValue().getTitle());
        assertEquals("약정 일급이 미지급됐습니다.", insert.getValue().getContent());
        verify(settlementMapper).transitionScheduledToOnHold(31L);
        verify(reviewQueueService).enqueue(any());
    }

    @Test
    void existingOpenDisputeRejectsDuplicateBeforeInsert() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(workCase(WorkCaseStatus.COMPLETED));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.ON_HOLD));
        when(disputeMapper.findOpenIdsForUpdate(WORK_CASE_ID)).thenReturn(List.of(90L));

        assertThrows(DisputeAlreadyOpenException.class, () -> disputeService.create(command(
                "임금 확인", "약정 일급이 미지급됐습니다.")));

        verify(disputeMapper, never()).insertOpen(any());
        verify(settlementMapper, never()).transitionScheduledToOnHold(31L);
    }

    @Test
    void noShowWaitingSettlementStaysWaitingWhileDisputeIsStored() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(workCase(WorkCaseStatus.NO_SHOW));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.WAITING));
        when(disputeMapper.findOpenIdsForUpdate(WORK_CASE_ID)).thenReturn(List.of());
        doAnswer(invocation -> {
            ((DisputeInsert) invocation.getArgument(0)).setReportId(92L);
            return 1;
        }).when(disputeMapper).insertOpen(any());

        assertEquals(92L, disputeService.create(command("노쇼 이견", "출근 처리에 이견이 있습니다.")));

        verify(settlementMapper, never()).transitionScheduledToOnHold(any());
        verify(reviewQueueService).enqueue(any());
    }

    @Test
    void nonPartyCannotLearnWhetherWorkCaseExists() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(workCase(WorkCaseStatus.COMPLETED));

        DisputeCreateCommand command = DisputeCreateCommand.builder()
                .workCaseId(WORK_CASE_ID)
                .requesterUserId(999L)
                .requesterRole(UserRole.WORKER)
                .title("임금 확인")
                .content("약정 일급이 미지급됐습니다.")
                .build();

        assertThrows(ResourceNotFoundException.class, () -> disputeService.create(command));
        verify(settlementMapper, never()).findByWorkCaseIdForUpdate(WORK_CASE_ID);
    }

    @Test
    void partyReadsPageWithoutInternalUserIdentifiers() {
        when(workSettlementService.findEscrowContext(WORK_CASE_ID))
                .thenReturn(workCase(WorkCaseStatus.COMPLETED));
        when(disputeMapper.countByWorkCaseId(WORK_CASE_ID)).thenReturn(1L);
        when(disputeMapper.findPageByWorkCaseId(WORK_CASE_ID, 20, 0L)).thenReturn(List.of(
                new DisputeListRow(
                        91L,
                        "임금 확인",
                        "약정 일급이 미지급됐습니다.",
                        DisputeStatus.OPEN,
                        null,
                        UserRole.WORKER,
                        LocalDateTime.of(2026, 8, 15, 10, 0),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)
        ));

        PageResponse<DisputeListItemResponse> page = disputeService.findPage(
                WORK_CASE_ID, WORKER_ID, UserRole.WORKER, 0, 20);

        assertEquals(1, page.getContent().size());
        assertEquals("2026-08-15T01:00:00Z", page.getContent().get(0).getCreatedAt().toString());
        assertNull(page.getContent().get(0).getDemoReview());
        assertEquals(1L, page.getPage().getTotalElements());
    }

    @Test
    void disabledReviewModeRejectsCreateBeforeDisputeOrHoldIsWritten() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(workCase(WorkCaseStatus.COMPLETED));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.SCHEDULED));
        doThrow(new DisputeReviewUnavailableException())
                .when(reviewQueueService).requireEnabled();

        assertThrows(DisputeReviewUnavailableException.class, () -> disputeService.create(command(
                "임금 확인", "약정 일급이 미지급됐습니다.")));

        verify(disputeMapper, never()).insertOpen(any());
        verify(settlementMapper, never()).transitionScheduledToOnHold(any());
    }

    @Test
    void partyReadsFailedReviewAsSafeDelayStateWithoutInternalFailureCode() {
        when(workSettlementService.findEscrowContext(WORK_CASE_ID))
                .thenReturn(workCase(WorkCaseStatus.COMPLETED));
        when(disputeMapper.countByWorkCaseId(WORK_CASE_ID)).thenReturn(1L);
        when(disputeMapper.findPageByWorkCaseId(WORK_CASE_ID, 20, 0L)).thenReturn(List.of(
                new DisputeListRow(
                        91L,
                        "임금 확인",
                        "약정 일급이 미지급됐습니다.",
                        DisputeStatus.UNDER_REVIEW,
                        null,
                        UserRole.WORKER,
                        LocalDateTime.of(2026, 8, 15, 10, 0),
                        null,
                        "SIMULATED_LLM",
                        DisputeReviewExecutionStatus.FAILED,
                        null,
                        null,
                        null,
                        null,
                        LocalDateTime.of(2026, 8, 15, 10, 1))
        ));

        DisputeListItemResponse item = disputeService.findPage(
                WORK_CASE_ID, WORKER_ID, UserRole.WORKER, 0, 20).getContent().get(0);

        assertNotNull(item.getDemoReview());
        assertEquals("FAILED", item.getDemoReview().getStatus());
        assertNull(item.getDemoReview().getDecision());
        assertEquals(List.of(), item.getDemoReview().getReasonCodes());
    }

    @Test
    void corruptedCompletedReviewIsReducedToSafeDelayInsteadOfFailingWholePage() {
        when(workSettlementService.findEscrowContext(WORK_CASE_ID))
                .thenReturn(workCase(WorkCaseStatus.COMPLETED));
        when(disputeMapper.countByWorkCaseId(WORK_CASE_ID)).thenReturn(1L);
        when(disputeMapper.findPageByWorkCaseId(WORK_CASE_ID, 20, 0L)).thenReturn(List.of(
                new DisputeListRow(
                        91L,
                        "임금 확인",
                        "약정 일급이 미지급됐습니다.",
                        DisputeStatus.RESOLVED,
                        "검토 완료",
                        UserRole.WORKER,
                        LocalDateTime.of(2026, 8, 15, 10, 0),
                        LocalDateTime.of(2026, 8, 15, 10, 1),
                        "SIMULATED_LLM",
                        DisputeReviewExecutionStatus.COMPLETED,
                        DisputeReviewDecision.RESOLVE,
                        "not-json",
                        "검토 완료",
                        java.math.BigDecimal.ONE,
                        LocalDateTime.of(2026, 8, 15, 10, 1))
        ));

        DisputeListItemResponse item = disputeService.findPage(
                WORK_CASE_ID, WORKER_ID, UserRole.WORKER, 0, 20).getContent().get(0);

        assertEquals("FAILED", item.getDemoReview().getStatus());
        assertNull(item.getDemoReview().getDecision());
    }

    @Test
    void disputeNotifiesOnlyTheCounterpartyOfTheReporter() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(workCase(WorkCaseStatus.COMPLETED));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.SCHEDULED));
        when(disputeMapper.findOpenIdsForUpdate(WORK_CASE_ID)).thenReturn(List.of());
        doAnswer(invocation -> {
            ((DisputeInsert) invocation.getArgument(0)).setReportId(93L);
            return 1;
        }).when(disputeMapper).insertOpen(any());
        when(settlementMapper.transitionScheduledToOnHold(31L)).thenReturn(1);

        disputeService.create(command("임금 확인", "약정 일급이 미지급됐습니다."));

        ArgumentCaptor<NotificationRecordCommand> recorded =
                ArgumentCaptor.forClass(NotificationRecordCommand.class);
        verify(notificationRecorder).record(recorded.capture());
        assertEquals(NotificationType.WAGE_REPORTED, recorded.getValue().getType());
        assertEquals(93L, recorded.getValue().getSourceId().longValue());
        assertEquals(WORK_CASE_ID, recorded.getValue().getWorkCaseId().longValue());
        assertEquals("주말 홀 서빙", recorded.getValue().getWorkCaseTitle());
        assertEquals(List.of(OWNER_ID), recorded.getValue().getRecipientUserIds());
    }

    @Test
    void ownerReportNotifiesWorkerInstead() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(workCase(WorkCaseStatus.COMPLETED));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.WAITING));
        when(disputeMapper.findOpenIdsForUpdate(WORK_CASE_ID)).thenReturn(List.of());
        doAnswer(invocation -> {
            ((DisputeInsert) invocation.getArgument(0)).setReportId(94L);
            return 1;
        }).when(disputeMapper).insertOpen(any());

        disputeService.create(DisputeCreateCommand.builder()
                .workCaseId(WORK_CASE_ID)
                .requesterUserId(OWNER_ID)
                .requesterRole(UserRole.OWNER)
                .title("근무 이견")
                .content("근무 시간에 이견이 있습니다.")
                .build());

        ArgumentCaptor<NotificationRecordCommand> recorded =
                ArgumentCaptor.forClass(NotificationRecordCommand.class);
        verify(notificationRecorder).record(recorded.capture());
        assertEquals(List.of(WORKER_ID), recorded.getValue().getRecipientUserIds());
    }

    @Test
    void rejectedDuplicateDisputeDoesNotNotifyAnyone() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(workCase(WorkCaseStatus.COMPLETED));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.ON_HOLD));
        when(disputeMapper.findOpenIdsForUpdate(WORK_CASE_ID)).thenReturn(List.of(90L));

        assertThrows(DisputeAlreadyOpenException.class, () -> disputeService.create(command(
                "임금 확인", "약정 일급이 미지급됐습니다.")));

        verify(notificationRecorder, never()).record(any());
    }

    private static DisputeCreateCommand command(String title, String content) {
        return DisputeCreateCommand.builder()
                .workCaseId(WORK_CASE_ID)
                .requesterUserId(WORKER_ID)
                .requesterRole(UserRole.WORKER)
                .title(title)
                .content(content)
                .build();
    }

    private static WorkCaseEscrowSnapshot workCase(WorkCaseStatus status) {
        return WorkCaseEscrowSnapshot.builder()
                .workCaseId(WORK_CASE_ID)
                .employerId(OWNER_ID)
                .workerId(WORKER_ID)
                .agreedWage(120_000L)
                .title("주말 홀 서빙")
                .status(status)
                .successfulCheckInCount(1L)
                .build();
    }

    private static SettlementSnapshot settlement(SettlementStatus status) {
        return SettlementSnapshot.builder()
                .settlementId(31L)
                .workCaseId(WORK_CASE_ID)
                .amount(120_000L)
                .status(status)
                .retryCount(0)
                .build();
    }
}
