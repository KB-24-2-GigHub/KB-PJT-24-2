package com.gighub.settlement.service;

import com.gighub.settlement.domain.SettlementStatus;
import com.gighub.settlement.dto.SettlementSnapshot;
import com.gighub.settlement.mapper.SettlementMapper;
import com.gighub.settlement.service.command.SettlementApproveCommand;
import com.gighub.settlement.service.impl.SettlementServiceImpl;
import com.gighub.settlement.service.result.SettlementResult;
import com.gighub.wallet.exception.EscrowAccessDeniedException;
import com.gighub.wallet.exception.EscrowIntegrityException;
import com.gighub.wallet.exception.InvalidEscrowStateException;
import com.gighub.wallet.service.SettlementWalletService;
import com.gighub.wallet.service.command.SettlementWalletCommand;
import com.gighub.work.contract.WorkCaseEscrowSnapshot;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.service.WorkSettlementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Settlement outer Transaction의 owner/participant 순서와 상태 방어를 고정합니다. */
@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {

    private static final Long EMPLOYER_ID = 3L;
    private static final Long WORKER_ID = 4L;
    private static final Long WORK_CASE_ID = 1L;
    private static final Long SETTLEMENT_ID = 12L;
    private static final Long AGREED_WAGE = 300_000L;
    private static final String KEY = "SETTLEMENT-KEY-001";
    private static final LocalDateTime PROCESSING_AT =
            LocalDateTime.of(2026, 7, 24, 17, 10);
    private static final LocalDateTime COMPLETED_AT =
            LocalDateTime.of(2026, 7, 24, 17, 10, 1, 123_456_000);

    @Mock
    private SettlementMapper settlementMapper;

    @Mock
    private WorkSettlementService workSettlementService;

    @Mock
    private SettlementWalletService settlementWalletService;

    @InjectMocks
    private SettlementServiceImpl settlementService;

    @Test
    void approveCallsParticipantsInLockAndCommitOrder() {
        WorkCaseEscrowSnapshot context = context(WorkCaseStatus.ACCEPTED);
        stubHappy(context);

        SettlementResult result = settlementService.approve(command(EMPLOYER_ID));

        assertEquals(SETTLEMENT_ID, result.getSettlementId());
        assertEquals("COMPLETED", result.getStatus());
        assertEquals(COMPLETED_AT, result.getCompletedAt());
        assertFalse(result.isReplayed());

        InOrder order = inOrder(
                workSettlementService, settlementMapper, settlementWalletService);
        order.verify(workSettlementService).lockEscrowContext(WORK_CASE_ID);
        order.verify(settlementMapper).findByWorkCaseIdForUpdate(WORK_CASE_ID);
        order.verify(settlementWalletService).verifyReplay(any());
        order.verify(settlementMapper)
                .transitionWaitingToProcessing(SETTLEMENT_ID, EMPLOYER_ID);
        order.verify(settlementWalletService).release(any());
        order.verify(workSettlementService).completeForPayout(context);
        order.verify(settlementMapper)
                .transitionProcessingToCompleted(SETTLEMENT_ID, EMPLOYER_ID);

        ArgumentCaptor<SettlementWalletCommand> commandCaptor =
                ArgumentCaptor.forClass(SettlementWalletCommand.class);
        verify(settlementWalletService).release(commandCaptor.capture());
        assertEquals(WORK_CASE_ID.longValue(), commandCaptor.getValue().getWorkCaseId());
        assertEquals(EMPLOYER_ID.longValue(), commandCaptor.getValue().getEmployerId());
        assertEquals(WORKER_ID.longValue(), commandCaptor.getValue().getWorkerId());
        assertEquals(AGREED_WAGE.longValue(), commandCaptor.getValue().getAmount());
    }

    @Test
    void approveReplaysOnlyCompletedSettlementAndWalletState() {
        WorkCaseEscrowSnapshot context = context(WorkCaseStatus.COMPLETED);
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID)).thenReturn(context);
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.COMPLETED));
        when(settlementWalletService.verifyReplay(any())).thenReturn(true);

        SettlementResult result = settlementService.approve(command(EMPLOYER_ID));

        assertTrue(result.isReplayed());
        verify(settlementWalletService, never()).release(any());
        verify(settlementMapper, never()).transitionWaitingToProcessing(anyLong(), anyLong());
    }

    @Test
    void approveRejectsMissingOrMismatchedSettlementBeforeWalletMutation() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(context(WorkCaseStatus.ACCEPTED));
        assertThrows(
                EscrowIntegrityException.class,
                () -> settlementService.approve(command(EMPLOYER_ID)));
        verify(settlementWalletService, never()).release(any());

        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.WAITING).toBuilder()
                        .amount(AGREED_WAGE - 1)
                        .build());
        assertThrows(
                EscrowIntegrityException.class,
                () -> settlementService.approve(command(EMPLOYER_ID)));
    }

    @Test
    void approvePreservesLegacyAllowedStates() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(context(WorkCaseStatus.CHECK_OUT_MISSING));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.WAITING));

        assertThrows(
                InvalidEscrowStateException.class,
                () -> settlementService.approve(command(EMPLOYER_ID)));
        verify(settlementWalletService, never()).release(any());
    }

    @Test
    void approveRejectsOnHoldProcessingAndCompletedWithoutReplay() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(context(WorkCaseStatus.ACCEPTED));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.ON_HOLD));
        assertThrows(
                InvalidEscrowStateException.class,
                () -> settlementService.approve(command(EMPLOYER_ID)));

        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.PROCESSING));
        assertThrows(
                EscrowIntegrityException.class,
                () -> settlementService.approve(command(EMPLOYER_ID)));

        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(context(WorkCaseStatus.COMPLETED));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.COMPLETED));
        assertThrows(
                InvalidEscrowStateException.class,
                () -> settlementService.approve(command(EMPLOYER_ID)));
    }

    @Test
    void approveRejectsBlockingDisputeBeforeProcessing() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(context(WorkCaseStatus.ACCEPTED));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.WAITING));
        when(settlementMapper.findBlockingDisputeIdsForUpdate(WORK_CASE_ID))
                .thenReturn(List.of(9L));

        assertThrows(
                InvalidEscrowStateException.class,
                () -> settlementService.approve(command(EMPLOYER_ID)));
        verify(settlementMapper, never()).transitionWaitingToProcessing(anyLong(), anyLong());
    }

    @Test
    void approveRejectsUnauthorizedOrSamePartyBeforeSettlementLock() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(context(WorkCaseStatus.ACCEPTED));
        assertThrows(
                EscrowAccessDeniedException.class,
                () -> settlementService.approve(command(99L)));
        verify(settlementMapper, never()).findByWorkCaseIdForUpdate(anyLong());

        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(context(WorkCaseStatus.ACCEPTED).toBuilder()
                        .workerId(EMPLOYER_ID)
                        .build());
        assertThrows(
                InvalidEscrowStateException.class,
                () -> settlementService.approve(command(EMPLOYER_ID)));
    }

    @Test
    void approveStopsOnExpectedStateFailuresAndReliesOnOuterRollback() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(context(WorkCaseStatus.ACCEPTED));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.WAITING));

        assertThrows(
                EscrowIntegrityException.class,
                () -> settlementService.approve(command(EMPLOYER_ID)));
        verify(settlementWalletService, never()).release(any());

        when(settlementMapper.transitionWaitingToProcessing(SETTLEMENT_ID, EMPLOYER_ID))
                .thenReturn(1);
        when(workSettlementService.completeForPayout(any())).thenReturn(true);
        when(settlementMapper.transitionProcessingToCompleted(SETTLEMENT_ID, EMPLOYER_ID))
                .thenReturn(0);
        assertThrows(
                EscrowIntegrityException.class,
                () -> settlementService.approve(command(EMPLOYER_ID)));
        verify(settlementWalletService).release(any());
        verify(workSettlementService).completeForPayout(any());
    }

    @Test
    void approveStopsWhenWorkCompletionParticipantRejectsTheExpectedState() {
        WorkCaseEscrowSnapshot context = context(WorkCaseStatus.ACCEPTED);
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID)).thenReturn(context);
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.WAITING));
        when(settlementMapper.transitionWaitingToProcessing(SETTLEMENT_ID, EMPLOYER_ID))
                .thenReturn(1);
        when(workSettlementService.completeForPayout(context)).thenReturn(false);

        assertThrows(
                EscrowIntegrityException.class,
                () -> settlementService.approve(command(EMPLOYER_ID)));

        verify(settlementWalletService).release(any());
        verify(settlementMapper, never())
                .transitionProcessingToCompleted(anyLong(), anyLong());
    }

    private void stubHappy(WorkCaseEscrowSnapshot context) {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID)).thenReturn(context);
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(
                        settlement(SettlementStatus.WAITING),
                        settlement(SettlementStatus.COMPLETED));
        when(settlementMapper.transitionWaitingToProcessing(SETTLEMENT_ID, EMPLOYER_ID))
                .thenReturn(1);
        when(workSettlementService.completeForPayout(context)).thenReturn(true);
        when(settlementMapper.transitionProcessingToCompleted(SETTLEMENT_ID, EMPLOYER_ID))
                .thenReturn(1);
    }

    private SettlementApproveCommand command(Long approverId) {
        return SettlementApproveCommand.builder()
                .workCaseId(WORK_CASE_ID)
                .approverUserId(approverId)
                .idempotencyKey(KEY)
                .build();
    }

    private WorkCaseEscrowSnapshot context(WorkCaseStatus status) {
        return WorkCaseEscrowSnapshot.builder()
                .workCaseId(WORK_CASE_ID)
                .employerId(EMPLOYER_ID)
                .workerId(WORKER_ID)
                .agreedWage(AGREED_WAGE)
                .status(status)
                .build();
    }

    private SettlementSnapshot settlement(SettlementStatus status) {
        SettlementSnapshot.SettlementSnapshotBuilder builder = SettlementSnapshot.builder()
                .settlementId(SETTLEMENT_ID)
                .workCaseId(WORK_CASE_ID)
                .amount(AGREED_WAGE)
                .status(status);
        if (status == SettlementStatus.COMPLETED) {
            builder.approvedByUserId(EMPLOYER_ID)
                    .processingAt(PROCESSING_AT)
                    .completedAt(COMPLETED_AT);
        }
        return builder.build();
    }
}
