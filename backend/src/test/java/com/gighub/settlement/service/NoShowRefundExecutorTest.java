package com.gighub.settlement.service;

import com.gighub.settlement.domain.SettlementStatus;
import com.gighub.settlement.dto.SettlementSnapshot;
import com.gighub.settlement.mapper.SettlementMapper;
import com.gighub.settlement.service.impl.NoShowRefundExecutorImpl;
import com.gighub.settlement.service.policy.SettlementPayoutDecision;
import com.gighub.settlement.service.policy.SettlementPayoutRejectedException;
import com.gighub.settlement.service.result.SettlementResult;
import com.gighub.wallet.domain.EscrowStatus;
import com.gighub.wallet.idempotency.WalletIdempotencyKeys;
import com.gighub.wallet.service.SettlementWalletService;
import com.gighub.wallet.service.SettlementWalletService.SettlementAmounts;
import com.gighub.wallet.service.SettlementWalletService.SettlementWalletLock;
import com.gighub.wallet.service.command.NoShowRefundWalletCommand;
import com.gighub.wallet.service.result.SettlementEscrowSnapshot;
import com.gighub.work.contract.WorkCaseEscrowSnapshot;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.service.WorkSettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NoShowRefundExecutorTest {

    private static final long OWNER_ID = 3L;
    private static final long WORKER_ID = 4L;
    private static final long WORK_CASE_ID = 1L;
    private static final long SETTLEMENT_ID = 12L;
    private static final long ESCROW_ID = 19L;
    private static final long WAGE = 300_000L;
    private static final LocalDateTime COMPLETED_AT =
            LocalDateTime.of(2026, 8, 13, 14, 0);

    @Mock
    private SettlementMapper settlementMapper;

    @Mock
    private WorkSettlementService workSettlementService;

    @Mock
    private SettlementWalletService settlementWalletService;

    private NoShowRefundExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new NoShowRefundExecutorImpl(
                settlementMapper, workSettlementService, settlementWalletService);
    }

    @Test
    void refundsOnlyTheOwnerInTheApprovedLockAndMutationOrder() {
        stubHappy();

        SettlementResult result = executor.execute(WORK_CASE_ID, OWNER_ID);

        assertEquals(SETTLEMENT_ID, result.getSettlementId());
        assertEquals("REFUNDED", result.getStatus());
        assertEquals(WAGE, result.getOriginalEscrowAmount());
        assertEquals(0L, result.getWorkerPaidAmount());
        assertEquals(WAGE, result.getOwnerRefundAmount());
        assertEquals(COMPLETED_AT, result.getCompletedAt());
        assertFalse(result.isReplayed());

        InOrder order = inOrder(
                workSettlementService, settlementMapper, settlementWalletService);
        order.verify(workSettlementService).lockEscrowContext(WORK_CASE_ID);
        order.verify(settlementMapper).findByWorkCaseIdForUpdate(WORK_CASE_ID);
        order.verify(settlementMapper).findBlockingDisputeIdsForUpdate(WORK_CASE_ID);
        order.verify(settlementWalletService).lockRefundEscrow(any());
        order.verify(settlementWalletService).lockRefundWallet(any(), anyLong());
        order.verify(settlementWalletService)
                .verifyHeldRefundEscrow(any(), anyLong(), any());
        order.verify(settlementMapper)
                .transitionWaitingToRefundProcessing(SETTLEMENT_ID, OWNER_ID);
        order.verify(settlementWalletService).refund(any(), anyLong(), any());
        order.verify(settlementMapper)
                .transitionRefundProcessingToRefunded(SETTLEMENT_ID, OWNER_ID);
        order.verify(settlementMapper).findByWorkCaseIdForUpdate(WORK_CASE_ID);
        order.verify(settlementWalletService).verifyCompletedRefund(any(), any());

        ArgumentCaptor<NoShowRefundWalletCommand> command =
                ArgumentCaptor.forClass(NoShowRefundWalletCommand.class);
        verify(settlementWalletService).refund(command.capture(), anyLong(), any());
        assertEquals(OWNER_ID, command.getValue().getEmployerId());
        assertEquals(
                WalletIdempotencyKeys.settlementRefundOwner(SETTLEMENT_ID),
                command.getValue().getEmployerLedgerKey());
        verify(settlementWalletService, never()).lockPayoutWallets(any(), anyLong());
        verify(settlementWalletService, never()).release(any(), anyLong(), any());
    }

    @Test
    void rejectsASuccessfulCheckInBeforeSettlementOrMoneyLocks() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(work(1L));

        assertRejected(
                SettlementPayoutDecision.NOT_READY,
                () -> executor.execute(WORK_CASE_ID, OWNER_ID));

        verify(settlementMapper, never()).findByWorkCaseIdForUpdate(anyLong());
        verify(settlementWalletService, never()).lockRefundEscrow(any());
    }

    @Test
    void openDisputeStopsBeforeWalletAndStateMutation() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID)).thenReturn(work(0L));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.WAITING));
        when(settlementMapper.findBlockingDisputeIdsForUpdate(WORK_CASE_ID))
                .thenReturn(List.of(55L));
        when(settlementWalletService.lockRefundEscrow(any())).thenReturn(heldEscrow());

        assertRejected(
                SettlementPayoutDecision.ON_HOLD,
                () -> executor.execute(WORK_CASE_ID, OWNER_ID));

        verify(settlementWalletService, never()).lockRefundWallet(any(), anyLong());
        verify(settlementWalletService, never()).refund(any(), anyLong(), any());
        verify(settlementMapper, never())
                .transitionWaitingToRefundProcessing(anyLong(), anyLong());
    }

    private void stubHappy() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID)).thenReturn(work(0L));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(
                        settlement(SettlementStatus.WAITING),
                        settlement(SettlementStatus.REFUNDED));
        when(settlementMapper.findBlockingDisputeIdsForUpdate(WORK_CASE_ID))
                .thenReturn(List.of());
        when(settlementWalletService.lockRefundEscrow(any())).thenReturn(heldEscrow());
        when(settlementWalletService.lockRefundWallet(any(), anyLong()))
                .thenReturn(walletLock());
        when(settlementMapper.transitionWaitingToRefundProcessing(
                SETTLEMENT_ID, OWNER_ID)).thenReturn(1);
        when(settlementWalletService.refund(any(), anyLong(), any()))
                .thenReturn(SettlementAmounts.fullRefund(WAGE));
        when(settlementMapper.transitionRefundProcessingToRefunded(
                SETTLEMENT_ID, OWNER_ID)).thenReturn(1);
    }

    private WorkCaseEscrowSnapshot work(long checkInCount) {
        return WorkCaseEscrowSnapshot.builder()
                .workCaseId(WORK_CASE_ID)
                .employerId(OWNER_ID)
                .workerId(WORKER_ID)
                .agreedWage(WAGE)
                .status(WorkCaseStatus.NO_SHOW)
                .successfulCheckInCount(checkInCount)
                .build();
    }

    private SettlementSnapshot settlement(SettlementStatus status) {
        SettlementSnapshot.SettlementSnapshotBuilder builder = SettlementSnapshot.builder()
                .settlementId(SETTLEMENT_ID)
                .workCaseId(WORK_CASE_ID)
                .amount(WAGE)
                .status(status)
                .retryCount(0);
        if (status == SettlementStatus.REFUNDED) {
            builder.approvedByUserId(OWNER_ID)
                    .processingAt(COMPLETED_AT.minusSeconds(1))
                    .completedAt(COMPLETED_AT);
        }
        return builder.build();
    }

    private SettlementEscrowSnapshot heldEscrow() {
        return SettlementEscrowSnapshot.builder()
                .escrowId(ESCROW_ID)
                .workCaseId(WORK_CASE_ID)
                .amount(WAGE)
                .status(EscrowStatus.HELD)
                .build();
    }

    private SettlementWalletLock walletLock() {
        return org.mockito.Mockito.mock(SettlementWalletLock.class);
    }

    private void assertRejected(
            SettlementPayoutDecision decision,
            org.junit.jupiter.api.function.Executable executable) {
        SettlementPayoutRejectedException rejected = assertThrows(
                SettlementPayoutRejectedException.class, executable);
        assertEquals(decision, rejected.getDecision());
    }
}
