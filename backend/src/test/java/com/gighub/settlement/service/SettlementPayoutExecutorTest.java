package com.gighub.settlement.service;

import com.gighub.common.exception.ResourceNotFoundException;
import com.gighub.idempotency.IdempotencyClaimService;
import com.gighub.member.domain.UserRole;
import com.gighub.settlement.domain.SettlementStatus;
import com.gighub.settlement.dto.SettlementSnapshot;
import com.gighub.settlement.exception.SettlementAlreadyProcessedException;
import com.gighub.settlement.exception.SettlementNotReadyException;
import com.gighub.settlement.exception.SettlementOnHoldException;
import com.gighub.settlement.mapper.SettlementMapper;
import com.gighub.settlement.service.command.SettlementApproveCommand;
import com.gighub.settlement.service.result.SettlementResult;
import com.gighub.wallet.exception.EscrowIntegrityException;
import com.gighub.wallet.exception.InvalidEscrowStateException;
import com.gighub.wallet.idempotency.WalletIdempotencyKeys;
import com.gighub.wallet.service.SettlementWalletService;
import com.gighub.wallet.service.SettlementWalletService.SettlementAmounts;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 원자 지급의 잠금·검증·변경 순서를 고정합니다. */
@ExtendWith(MockitoExtension.class)
class SettlementPayoutExecutorTest {

    private static final long EMPLOYER_ID = 3L;
    private static final long WORKER_ID = 4L;
    private static final long WORK_CASE_ID = 1L;
    private static final long SETTLEMENT_ID = 12L;
    private static final long ESCROW_ID = 19L;
    private static final long WAGE = 300_000L;
    private static final long CLAIM_ID = 77L;
    private static final LocalDateTime DUE_AT = LocalDateTime.of(2026, 8, 13, 10, 0);
    private static final LocalDateTime PROCESSING_AT = LocalDateTime.of(2026, 8, 12, 10, 1);
    private static final LocalDateTime COMPLETED_AT = LocalDateTime.of(2026, 8, 12, 10, 1, 1);

    @Mock
    private SettlementMapper settlementMapper;

    @Mock
    private WorkSettlementService workSettlementService;

    @Mock
    private SettlementWalletService settlementWalletService;

    @Mock
    private IdempotencyClaimService claimService;

    @Mock
    private SettlementReplayCodec replayCodec;

    @InjectMocks
    private SettlementPayoutExecutor executor;

    @Test
    void paysOnlyCompletedAndScheduledInTheApprovedLockOrder() {
        SettlementApproveCommand command = command("HTTP-KEY-IGNORED-BY-LEDGER");
        stubHappy();
        when(replayCodec.writeResponseBody(any())).thenReturn("{\"data\":{}}" );

        SettlementResult result = executor.execute(command, CLAIM_ID);

        assertEquals(SETTLEMENT_ID, result.getSettlementId());
        assertEquals("COMPLETED", result.getStatus());
        assertEquals(WAGE, result.getSettlementAmount());
        assertEquals(WAGE, result.getOriginalEscrowAmount());
        assertEquals(WAGE, result.getWorkerPaidAmount());
        assertEquals(0L, result.getOwnerRefundAmount());
        assertEquals(COMPLETED_AT, result.getCompletedAt());
        assertFalse(result.isReplayed());

        InOrder order = inOrder(
                workSettlementService,
                settlementMapper,
                settlementWalletService,
                claimService);
        order.verify(workSettlementService).lockEscrowContext(WORK_CASE_ID);
        order.verify(settlementMapper).findByWorkCaseIdForUpdate(WORK_CASE_ID);
        order.verify(settlementWalletService).lockHeldEscrow(any());
        order.verify(settlementMapper).findBlockingDisputeIdsForUpdate(WORK_CASE_ID);
        order.verify(settlementMapper)
                .transitionScheduledToProcessing(SETTLEMENT_ID, EMPLOYER_ID);
        order.verify(settlementWalletService).release(any(), anyLong());
        order.verify(settlementMapper)
                .transitionProcessingToCompleted(SETTLEMENT_ID, EMPLOYER_ID);
        order.verify(settlementMapper).findByWorkCaseIdForUpdate(WORK_CASE_ID);
        order.verify(claimService).complete(CLAIM_ID, 200, "{\"data\":{}}");

        ArgumentCaptor<SettlementWalletCommand> walletCommand =
                ArgumentCaptor.forClass(SettlementWalletCommand.class);
        verify(settlementWalletService).lockHeldEscrow(walletCommand.capture());
        assertEquals(
                WalletIdempotencyKeys.settlementReleaseOwner(SETTLEMENT_ID),
                walletCommand.getValue().getEmployerLedgerKey());
        assertEquals(
                WalletIdempotencyKeys.settlementReleaseWorker(SETTLEMENT_ID),
                walletCommand.getValue().getWorkerLedgerKey());
    }

    @Test
    void rejectsNonCompletedWorkWithoutChangingOrLockingSettlement() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(context(WorkCaseStatus.ACCEPTED));

        assertThrows(
                SettlementNotReadyException.class,
                () -> executor.execute(command("KEY"), CLAIM_ID));

        verify(settlementMapper, never()).findByWorkCaseIdForUpdate(anyLong());
        verify(settlementWalletService, never()).lockHeldEscrow(any());
    }

    @Test
    void rejectsAnotherOwnerAsNotFoundBeforeSettlementDetailsAreRead() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(context(WorkCaseStatus.COMPLETED).toBuilder()
                        .employerId(99L)
                        .build());

        assertThrows(
                ResourceNotFoundException.class,
                () -> executor.execute(command("KEY"), CLAIM_ID));

        verify(settlementMapper, never()).findByWorkCaseIdForUpdate(anyLong());
    }

    @Test
    void onHoldAndCompletedHaveDistinctApprovedConflicts() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(context(WorkCaseStatus.COMPLETED));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.ON_HOLD));
        assertThrows(
                SettlementOnHoldException.class,
                () -> executor.execute(command("KEY-A"), CLAIM_ID));

        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.COMPLETED));
        assertThrows(
                SettlementAlreadyProcessedException.class,
                () -> executor.execute(command("KEY-B"), CLAIM_ID));

        verify(settlementWalletService, never()).release(any(), anyLong());
    }

    @Test
    void openDisputeBlocksAfterEscrowLockAndBeforeAnyMoneyMutation() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(context(WorkCaseStatus.COMPLETED));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.SCHEDULED));
        when(settlementWalletService.lockHeldEscrow(any())).thenReturn(ESCROW_ID);
        when(settlementMapper.findBlockingDisputeIdsForUpdate(WORK_CASE_ID))
                .thenReturn(List.of(55L));

        assertThrows(
                SettlementOnHoldException.class,
                () -> executor.execute(command("KEY"), CLAIM_ID));

        verify(settlementWalletService, never()).release(any(), anyLong());
        verify(settlementMapper, never()).transitionScheduledToProcessing(anyLong(), anyLong());
    }

    @Test
    void aNonHeldEscrowMapsToTheApprovedNotReadyError() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(context(WorkCaseStatus.COMPLETED));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.SCHEDULED));
        when(settlementWalletService.lockHeldEscrow(any()))
                .thenThrow(new InvalidEscrowStateException("not held"));

        assertThrows(
                SettlementNotReadyException.class,
                () -> executor.execute(command("KEY"), CLAIM_ID));
    }

    @Test
    void aFailureAfterWalletMutationDoesNotCompleteTheClaim() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(context(WorkCaseStatus.COMPLETED));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement(SettlementStatus.SCHEDULED));
        when(settlementWalletService.lockHeldEscrow(any())).thenReturn(ESCROW_ID);
        when(settlementMapper.findBlockingDisputeIdsForUpdate(WORK_CASE_ID))
                .thenReturn(List.of());
        when(settlementMapper.transitionScheduledToProcessing(SETTLEMENT_ID, EMPLOYER_ID))
                .thenReturn(1);
        when(settlementWalletService.release(any(), anyLong()))
                .thenReturn(SettlementAmounts.fullPayout(WAGE));
        when(settlementMapper.transitionProcessingToCompleted(SETTLEMENT_ID, EMPLOYER_ID))
                .thenReturn(0);

        assertThrows(
                EscrowIntegrityException.class,
                () -> executor.execute(command("KEY"), CLAIM_ID));

        verify(settlementWalletService).release(any(), anyLong());
        verify(claimService, never()).complete(anyLong(), any(Integer.class), any());
    }

    @Test
    void rejectsACompletedMoneyResultThatDoesNotMatchTheSettlementAmount() {
        SettlementApproveCommand command = command("KEY");
        stubHappy();
        when(settlementWalletService.release(any(), anyLong()))
                .thenReturn(SettlementAmounts.fullPayout(WAGE - 1));

        assertThrows(
                EscrowIntegrityException.class,
                () -> executor.execute(command, CLAIM_ID));

        verify(claimService, never()).complete(anyLong(), any(Integer.class), any());
    }

    private void stubHappy() {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(context(WorkCaseStatus.COMPLETED));
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(
                        settlement(SettlementStatus.SCHEDULED),
                        settlement(SettlementStatus.COMPLETED));
        when(settlementWalletService.lockHeldEscrow(any())).thenReturn(ESCROW_ID);
        when(settlementMapper.findBlockingDisputeIdsForUpdate(WORK_CASE_ID))
                .thenReturn(List.of());
        when(settlementMapper.transitionScheduledToProcessing(SETTLEMENT_ID, EMPLOYER_ID))
                .thenReturn(1);
        when(settlementWalletService.release(any(), anyLong()))
                .thenReturn(SettlementAmounts.fullPayout(WAGE));
        when(settlementMapper.transitionProcessingToCompleted(SETTLEMENT_ID, EMPLOYER_ID))
                .thenReturn(1);
    }

    private SettlementApproveCommand command(String externalKey) {
        return SettlementApproveCommand.builder()
                .workCaseId(WORK_CASE_ID)
                .approverUserId(EMPLOYER_ID)
                .approverRole(UserRole.OWNER)
                .idempotencyKey(externalKey)
                .build();
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

    private SettlementSnapshot settlement(SettlementStatus status) {
        SettlementSnapshot.SettlementSnapshotBuilder builder = SettlementSnapshot.builder()
                .settlementId(SETTLEMENT_ID)
                .workCaseId(WORK_CASE_ID)
                .amount(WAGE)
                .status(status)
                .dueAt(DUE_AT);
        if (status == SettlementStatus.COMPLETED) {
            builder.approvedByUserId(EMPLOYER_ID)
                    .processingAt(PROCESSING_AT)
                    .completedAt(COMPLETED_AT);
        }
        return builder.build();
    }
}
