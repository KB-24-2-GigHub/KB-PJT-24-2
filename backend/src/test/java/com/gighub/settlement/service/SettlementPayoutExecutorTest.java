package com.gighub.settlement.service;

import com.gighub.settlement.domain.SettlementStatus;
import com.gighub.settlement.dto.SettlementSnapshot;
import com.gighub.settlement.mapper.SettlementMapper;
import com.gighub.settlement.service.impl.SettlementPayoutExecutorImpl;
import com.gighub.settlement.service.command.SettlementPayoutCommand;
import com.gighub.settlement.service.policy.SettlementPayoutDecision;
import com.gighub.settlement.service.policy.SettlementPayoutRejectedException;
import com.gighub.settlement.service.result.SettlementResult;
import com.gighub.wallet.domain.EscrowStatus;
import com.gighub.wallet.exception.EscrowIntegrityException;
import com.gighub.wallet.idempotency.WalletIdempotencyKeys;
import com.gighub.wallet.service.SettlementWalletService;
import com.gighub.wallet.service.SettlementWalletService.SettlementAmounts;
import com.gighub.wallet.service.SettlementWalletService.SettlementWalletLock;
import com.gighub.wallet.service.command.SettlementWalletCommand;
import com.gighub.wallet.service.result.SettlementEscrowSnapshot;
import com.gighub.work.contract.WorkCaseEscrowSnapshot;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.service.WorkSettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.gighub.notification.service.NotificationRecorder;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 수동·자동 호출자가 공유하는 원자 지급 순서와 결과 대사를 고정합니다. */
@ExtendWith(MockitoExtension.class)
class SettlementPayoutExecutorTest {

    private static final long EMPLOYER_ID = 3L;
    private static final long WORKER_ID = 4L;
    private static final long WORK_CASE_ID = 1L;
    private static final long SETTLEMENT_ID = 12L;
    private static final long ESCROW_ID = 19L;
    private static final long WAGE = 300_000L;
    private static final LocalDateTime DUE_AT = LocalDateTime.of(2026, 8, 13, 10, 0);
    private static final LocalDateTime PROCESSING_AT = LocalDateTime.of(2026, 8, 12, 10, 1);
    private static final LocalDateTime COMPLETED_AT =
            LocalDateTime.of(2026, 8, 12, 10, 1, 1);

    @Mock
    private SettlementMapper settlementMapper;

    @Mock
    private WorkSettlementService workSettlementService;

    @Mock
    private SettlementWalletService settlementWalletService;

    private SettlementPayoutExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new SettlementPayoutExecutorImpl(
                settlementMapper,
                workSettlementService,
                settlementWalletService,
                mock(NotificationRecorder.class));
    }

    @Test
    void paysOnlyCompletedAndScheduledInTheApprovedLockOrder() {
        stubHappy();

        SettlementResult result = executor.execute(ownerCommand());

        assertEquals(SETTLEMENT_ID, result.getSettlementId());
        assertEquals("COMPLETED", result.getStatus());
        assertEquals(WAGE, result.getSettlementAmount());
        assertEquals(WAGE, result.getOriginalEscrowAmount());
        assertEquals(WAGE, result.getWorkerPaidAmount());
        assertEquals(0L, result.getOwnerRefundAmount());
        assertEquals(COMPLETED_AT, result.getCompletedAt());
        assertFalse(result.isReplayed());

        InOrder order = inOrder(
                workSettlementService, settlementMapper, settlementWalletService);
        order.verify(workSettlementService).lockEscrowContext(WORK_CASE_ID);
        order.verify(settlementMapper).findByWorkCaseIdForUpdate(WORK_CASE_ID);
        order.verify(settlementMapper).findBlockingDisputeIdsForUpdate(WORK_CASE_ID);
        order.verify(settlementWalletService).lockEscrow(any());
        order.verify(settlementWalletService).lockPayoutWallets(any(), anyLong());
        order.verify(settlementWalletService).verifyHeldEscrow(any(), anyLong(), any());
        order.verify(settlementMapper)
                .transitionScheduledToProcessing(SETTLEMENT_ID, EMPLOYER_ID);
        order.verify(settlementWalletService).release(any(), anyLong(), any());
        order.verify(settlementMapper)
                .transitionProcessingToCompleted(SETTLEMENT_ID, EMPLOYER_ID);
        order.verify(settlementMapper).findByWorkCaseIdForUpdate(WORK_CASE_ID);
        order.verify(settlementWalletService).verifyCompletedPayout(any(), any());

        ArgumentCaptor<SettlementWalletCommand> walletCommand =
                ArgumentCaptor.forClass(SettlementWalletCommand.class);
        verify(settlementWalletService).lockEscrow(walletCommand.capture());
        assertEquals(
                WalletIdempotencyKeys.settlementReleaseOwner(SETTLEMENT_ID),
                walletCommand.getValue().getEmployerLedgerKey());
        assertEquals(
                WalletIdempotencyKeys.settlementReleaseWorker(SETTLEMENT_ID),
                walletCommand.getValue().getWorkerLedgerKey());
    }

    @Test
    void scheduledCallerUsesTheSameExecutorWithANullApproverAndStableLedgerKeys() {
        stubHappyScheduled();

        SettlementResult result = executor.execute(
                SettlementPayoutCommand.scheduled(
                        SETTLEMENT_ID, WORK_CASE_ID, DUE_AT));

        assertEquals("COMPLETED", result.getStatus());
        verify(settlementMapper)
                .transitionEligibleScheduledToProcessing(SETTLEMENT_ID, DUE_AT);
        verify(settlementMapper)
                .transitionProcessingToCompleted(SETTLEMENT_ID, null);
        ArgumentCaptor<SettlementWalletCommand> walletCommand =
                ArgumentCaptor.forClass(SettlementWalletCommand.class);
        verify(settlementWalletService).release(
                walletCommand.capture(), anyLong(), any());
        assertEquals(
                WalletIdempotencyKeys.settlementReleaseOwner(SETTLEMENT_ID),
                walletCommand.getValue().getEmployerLedgerKey());
        assertEquals(
                WalletIdempotencyKeys.settlementReleaseWorker(SETTLEMENT_ID),
                walletCommand.getValue().getWorkerLedgerKey());
    }

    @Test
    void scheduledCallerRejectsAStaleSettlementCandidateId() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(context(WorkCaseStatus.COMPLETED));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.SCHEDULED));

        assertRejected(
                SettlementPayoutDecision.INTEGRITY_VIOLATION,
                () -> executor.execute(SettlementPayoutCommand.scheduled(
                        SETTLEMENT_ID + 1, WORK_CASE_ID, DUE_AT)));

        verify(settlementMapper, never())
                .transitionEligibleScheduledToProcessing(anyLong(), any());
        verify(settlementWalletService, never()).release(any(), anyLong(), any());
    }

    @Test
    void rejectsNonCompletedWorkBeforeSettlementAndEscrowLocks() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(context(WorkCaseStatus.ACCEPTED));

        assertRejected(
                SettlementPayoutDecision.NOT_READY,
                () -> executor.execute(ownerCommand()));

        verify(settlementMapper, never()).findByWorkCaseIdForUpdate(anyLong());
        verify(settlementWalletService, never()).lockEscrow(any());
    }

    @Test
    void rejectsAnotherOwnerAsNotFoundBeforeSettlementDetailsAreRead() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(context(WorkCaseStatus.COMPLETED).toBuilder()
                        .employerId(99L)
                        .build());

        assertRejected(
                SettlementPayoutDecision.RESOURCE_NOT_FOUND,
                () -> executor.execute(ownerCommand()));

        verify(settlementMapper, never()).findByWorkCaseIdForUpdate(anyLong());
    }

    @Test
    void onHoldAndCompletedHaveDistinctApprovedConflicts() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(context(WorkCaseStatus.COMPLETED));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.ON_HOLD));
        assertRejected(
                SettlementPayoutDecision.ON_HOLD,
                () -> executor.execute(ownerCommand()));

        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.COMPLETED));
        assertRejected(
                SettlementPayoutDecision.ALREADY_PROCESSED,
                () -> executor.execute(ownerCommand()));

        verify(settlementWalletService, never()).release(any(), anyLong(), any());
    }

    @Test
    void openDisputeBlocksAfterEscrowLockAndBeforeAnyMoneyMutation() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(context(WorkCaseStatus.COMPLETED));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.SCHEDULED));
        when(settlementWalletService.lockEscrow(any())).thenReturn(heldEscrow());
        when(settlementMapper.findBlockingDisputeIdsForUpdate(WORK_CASE_ID))
                .thenReturn(List.of(55L));

        assertRejected(
                SettlementPayoutDecision.ON_HOLD,
                () -> executor.execute(ownerCommand()));

        verify(settlementWalletService, never()).release(any(), anyLong(), any());
        verify(settlementMapper, never())
                .transitionScheduledToProcessing(anyLong(), anyLong());
    }

    @Test
    void aNonHeldEscrowMapsToTheApprovedNotReadyError() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(context(WorkCaseStatus.COMPLETED));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.SCHEDULED));
        when(settlementWalletService.lockEscrow(any()))
                .thenReturn(heldEscrow().toBuilder().status(EscrowStatus.RELEASED).build());
        when(settlementMapper.findBlockingDisputeIdsForUpdate(WORK_CASE_ID))
                .thenReturn(List.of());

        assertRejected(
                SettlementPayoutDecision.NOT_READY,
                () -> executor.execute(ownerCommand()));
    }

    @Test
    void aFailureAfterWalletMutationStopsBeforeResultVerification() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(context(WorkCaseStatus.COMPLETED));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.SCHEDULED));
        when(settlementWalletService.lockEscrow(any())).thenReturn(heldEscrow());
        when(settlementMapper.findBlockingDisputeIdsForUpdate(WORK_CASE_ID))
                .thenReturn(List.of());
        when(settlementWalletService.lockPayoutWallets(any(), anyLong()))
                .thenReturn(walletLock());
        when(settlementMapper.transitionScheduledToProcessing(SETTLEMENT_ID, EMPLOYER_ID))
                .thenReturn(1);
        when(settlementWalletService.release(any(), anyLong(), any()))
                .thenReturn(SettlementAmounts.fullPayout(WAGE));
        when(settlementMapper.transitionProcessingToCompleted(SETTLEMENT_ID, EMPLOYER_ID))
                .thenReturn(0);

        assertThrows(
                EscrowIntegrityException.class,
                () -> executor.execute(ownerCommand()));

        verify(settlementWalletService, never()).verifyCompletedPayout(any(), any());
    }

    @Test
    void rejectsACompletedMoneyResultThatDoesNotMatchTheSettlementAmount() {
        stubHappy();
        when(settlementWalletService.release(any(), anyLong(), any()))
                .thenReturn(SettlementAmounts.fullPayout(WAGE - 1));

        assertThrows(
                EscrowIntegrityException.class,
                () -> executor.execute(ownerCommand()));
    }

    private void stubHappy() {
        stubHappy(EMPLOYER_ID);
    }

    private void stubHappy(Long approvedByUserId) {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(context(WorkCaseStatus.COMPLETED));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(
                        settlement(SettlementStatus.SCHEDULED),
                        settlement(SettlementStatus.COMPLETED, approvedByUserId));
        when(settlementWalletService.lockEscrow(any())).thenReturn(heldEscrow());
        when(settlementMapper.findBlockingDisputeIdsForUpdate(WORK_CASE_ID))
                .thenReturn(List.of());
        when(settlementWalletService.lockPayoutWallets(any(), anyLong()))
                .thenReturn(walletLock());
        when(settlementMapper.transitionScheduledToProcessing(
                SETTLEMENT_ID, approvedByUserId))
                .thenReturn(1);
        when(settlementWalletService.release(any(), anyLong(), any()))
                .thenReturn(SettlementAmounts.fullPayout(WAGE));
        when(settlementMapper.transitionProcessingToCompleted(
                SETTLEMENT_ID, approvedByUserId))
                .thenReturn(1);
    }

    private void stubHappyScheduled() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(context(WorkCaseStatus.COMPLETED));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(
                        settlement(SettlementStatus.SCHEDULED),
                        settlement(SettlementStatus.COMPLETED, null));
        when(settlementWalletService.lockEscrow(any())).thenReturn(heldEscrow());
        when(settlementMapper.findBlockingDisputeIdsForUpdate(WORK_CASE_ID))
                .thenReturn(List.of());
        when(settlementWalletService.lockPayoutWallets(any(), anyLong()))
                .thenReturn(walletLock());
        when(settlementMapper.transitionEligibleScheduledToProcessing(
                SETTLEMENT_ID, DUE_AT)).thenReturn(1);
        when(settlementWalletService.release(any(), anyLong(), any()))
                .thenReturn(SettlementAmounts.fullPayout(WAGE));
        when(settlementMapper.transitionProcessingToCompleted(SETTLEMENT_ID, null))
                .thenReturn(1);
    }

    private SettlementPayoutCommand ownerCommand() {
        return SettlementPayoutCommand.ownerApproval(WORK_CASE_ID, EMPLOYER_ID);
    }

    private SettlementWalletLock walletLock() {
        return org.mockito.Mockito.mock(SettlementWalletLock.class);
    }

    private WorkCaseEscrowSnapshot context(WorkCaseStatus status) {
        return WorkCaseEscrowSnapshot.builder()
                .workCaseId(WORK_CASE_ID)
                .employerId(EMPLOYER_ID)
                .workerId(WORKER_ID)
                .agreedWage(WAGE)
                .status(status)
                .build();
    }

    private SettlementEscrowSnapshot heldEscrow() {
        return SettlementEscrowSnapshot.builder()
                .escrowId(ESCROW_ID)
                .workCaseId(WORK_CASE_ID)
                .amount(WAGE)
                .status(EscrowStatus.HELD)
                .build();
    }

    private SettlementSnapshot settlement(SettlementStatus status) {
        return settlement(status, status == SettlementStatus.COMPLETED ? EMPLOYER_ID : null);
    }

    private SettlementSnapshot settlement(
            SettlementStatus status, Long approvedByUserId) {
        SettlementSnapshot.SettlementSnapshotBuilder builder = SettlementSnapshot.builder()
                .settlementId(SETTLEMENT_ID)
                .workCaseId(WORK_CASE_ID)
                .amount(WAGE)
                .status(status)
                .dueAt(DUE_AT)
                .retryCount(0);
        if (status == SettlementStatus.COMPLETED) {
            builder.approvedByUserId(approvedByUserId)
                    .processingAt(PROCESSING_AT)
                    .completedAt(COMPLETED_AT);
        }
        return builder.build();
    }

    private void assertRejected(
            SettlementPayoutDecision decision,
            org.junit.jupiter.api.function.Executable executable) {
        SettlementPayoutRejectedException rejected = assertThrows(
                SettlementPayoutRejectedException.class, executable);
        assertEquals(decision, rejected.getDecision());
    }
}
