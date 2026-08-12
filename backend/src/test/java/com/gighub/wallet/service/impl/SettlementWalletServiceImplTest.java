package com.gighub.wallet.service.impl;

import com.gighub.wallet.dto.WalletBalanceSnapshot;
import com.gighub.wallet.dto.WalletTransactionSnapshot;
import com.gighub.wallet.exception.EscrowIntegrityException;
import com.gighub.wallet.exception.IdempotencyKeyReusedException;
import com.gighub.wallet.mapper.WalletMapper;
import com.gighub.wallet.mapper.param.WalletTransactionParam;
import com.gighub.wallet.service.command.SettlementWalletCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementWalletServiceImplTest {

    private static final long WORK_CASE_ID = 1L;
    private static final long EMPLOYER_ID = 3L;
    private static final long WORKER_ID = 4L;
    private static final long ESCROW_ID = 11L;
    private static final long AMOUNT = 300_000L;

    @Mock
    private WalletMapper walletMapper;

    @InjectMocks
    private SettlementWalletServiceImpl service;

    @Test
    void releaseLocksWalletsByUserIdAndWritesLedgersForTheOriginalRoles() {
        SettlementWalletCommand command = command(8L, 2L);
        stubEscrowLock(command, holdLedger(8L, 80L));
        stubRelease(command, wallet(80L, 8L, 400_000L, AMOUNT),
                wallet(20L, 2L, 0L, 0L));

        long escrowId = service.lockHeldEscrow(command);
        service.release(command, escrowId);

        InOrder lockOrder = inOrder(walletMapper);
        lockOrder.verify(walletMapper).getEscrowStatusForUpdate(WORK_CASE_ID);
        lockOrder.verify(walletMapper).getWalletSnapshotForUpdate(2L);
        lockOrder.verify(walletMapper).getWalletSnapshotForUpdate(8L);

        ArgumentCaptor<WalletTransactionParam> captor =
                ArgumentCaptor.forClass(WalletTransactionParam.class);
        verify(walletMapper, times(2)).insertWalletTransaction(captor.capture());
        WalletTransactionParam employer = captor.getAllValues().get(0);
        WalletTransactionParam worker = captor.getAllValues().get(1);
        assertEquals(80L, employer.getWalletId());
        assertEquals(AMOUNT, employer.getLockedBefore());
        assertEquals(0L, employer.getLockedAfter());
        assertEquals(command.getEmployerLedgerKey(), employer.getIdempotencyKey());
        assertEquals(20L, worker.getWalletId());
        assertEquals(0L, worker.getAvailableBefore());
        assertEquals(AMOUNT, worker.getAvailableAfter());
        assertEquals(command.getWorkerLedgerKey(), worker.getIdempotencyKey());
    }

    @Test
    void verifyReplayAcceptsOnlyACompleteLedgerPairAndReleasedEscrow() {
        SettlementWalletCommand command = command(EMPLOYER_ID, WORKER_ID);
        when(walletMapper.findSettlementTransactionByIdempotencyKeyForShare(
                command.getEmployerLedgerKey()))
                .thenReturn(releaseLedger(EMPLOYER_ID, 30L));
        when(walletMapper.findSettlementTransactionByIdempotencyKeyForShare(
                command.getWorkerLedgerKey()))
                .thenReturn(releaseLedger(WORKER_ID, 40L));
        when(walletMapper.getEscrowIdByWorkCaseId(WORK_CASE_ID)).thenReturn(ESCROW_ID);
        when(walletMapper.getEscrowStatusForUpdate(WORK_CASE_ID)).thenReturn("RELEASED");

        assertTrue(service.verifyReplay(command));
    }

    @Test
    void verifyReplayReturnsFalseForNoLedgerAndRejectsAnIncompletePair() {
        SettlementWalletCommand command = command(EMPLOYER_ID, WORKER_ID);

        assertFalse(service.verifyReplay(command));

        when(walletMapper.findSettlementTransactionByIdempotencyKeyForShare(
                command.getEmployerLedgerKey()))
                .thenReturn(releaseLedger(EMPLOYER_ID, 30L));
        assertThrows(EscrowIntegrityException.class, () -> service.verifyReplay(command));
    }

    @Test
    void releaseRejectsAChangedEmployerBeforeAnyMoneyMutation() {
        SettlementWalletCommand command = command(8L, WORKER_ID);
        stubEscrowLock(command, holdLedger(EMPLOYER_ID, 30L));

        assertThrows(EscrowIntegrityException.class, () -> service.lockHeldEscrow(command));

        verify(walletMapper, never()).getWalletSnapshotForUpdate(anyLong());
        verify(walletMapper, never()).releaseEscrow(anyLong());
        verify(walletMapper, never()).releaseLockedFunds(anyLong(), anyLong());
        verify(walletMapper, never()).addAvailableBalance(anyLong(), anyLong());
    }

    @Test
    void releaseTranslatesAConcurrentLedgerCollision() {
        SettlementWalletCommand command = command(EMPLOYER_ID, WORKER_ID);
        stubRelease(command, wallet(30L, EMPLOYER_ID, 400_000L, AMOUNT),
                wallet(40L, WORKER_ID, 0L, 0L));
        when(walletMapper.insertWalletTransaction(any()))
                .thenReturn(1)
                .thenThrow(new DuplicateKeyException("concurrent ledger"));

        assertThrows(
                IdempotencyKeyReusedException.class,
                () -> service.release(command, ESCROW_ID));
        verify(walletMapper, times(2)).insertWalletTransaction(any());
    }

    @Test
    void lockHeldEscrowRejectsNonHeldStateBeforeWalletLocks() {
        SettlementWalletCommand command = command(EMPLOYER_ID, WORKER_ID);
        when(walletMapper.getEscrowStatusForUpdate(WORK_CASE_ID)).thenReturn("RELEASED");

        assertThrows(
                com.gighub.wallet.exception.InvalidEscrowStateException.class,
                () -> service.lockHeldEscrow(command));

        verify(walletMapper, never()).getWalletSnapshotForUpdate(anyLong());
    }

    private void stubRelease(
            SettlementWalletCommand command,
            WalletBalanceSnapshot employer,
            WalletBalanceSnapshot worker) {
        long firstId = Math.min(command.getEmployerId(), command.getWorkerId());
        long secondId = Math.max(command.getEmployerId(), command.getWorkerId());
        WalletBalanceSnapshot first = firstId == command.getEmployerId() ? employer : worker;
        WalletBalanceSnapshot second = secondId == command.getEmployerId() ? employer : worker;
        when(walletMapper.getWalletSnapshotForUpdate(firstId)).thenReturn(first);
        when(walletMapper.getWalletSnapshotForUpdate(secondId)).thenReturn(second);
        when(walletMapper.releaseEscrow(WORK_CASE_ID)).thenReturn(1);
        when(walletMapper.releaseLockedFunds(command.getEmployerId(), AMOUNT)).thenReturn(1);
        when(walletMapper.addAvailableBalance(command.getWorkerId(), AMOUNT)).thenReturn(1);
        when(walletMapper.insertWalletTransaction(any())).thenReturn(1);
    }

    private void stubEscrowLock(
            SettlementWalletCommand command,
            WalletTransactionSnapshot hold) {
        when(walletMapper.getEscrowStatusForUpdate(WORK_CASE_ID)).thenReturn("HELD");
        when(walletMapper.getHeldEscrowAmount(WORK_CASE_ID)).thenReturn(AMOUNT);
        when(walletMapper.getEscrowIdByWorkCaseId(WORK_CASE_ID)).thenReturn(ESCROW_ID);
        when(walletMapper.findEscrowHoldTransactionSnapshot(WORK_CASE_ID, ESCROW_ID))
                .thenReturn(hold);
    }

    private SettlementWalletCommand command(long employerId, long workerId) {
        return SettlementWalletCommand.builder()
                .workCaseId(WORK_CASE_ID)
                .employerId(employerId)
                .workerId(workerId)
                .amount(AMOUNT)
                .employerLedgerKey("release-employer")
                .workerLedgerKey("release-worker")
                .build();
    }

    private WalletBalanceSnapshot wallet(
            long walletId, long userId, long available, long locked) {
        return WalletBalanceSnapshot.builder()
                .walletId(walletId)
                .userId(userId)
                .availableBalance(available)
                .lockedBalance(locked)
                .build();
    }

    private WalletTransactionSnapshot holdLedger(long ownerId, long walletId) {
        return WalletTransactionSnapshot.builder()
                .id(1L)
                .walletId(walletId)
                .walletUserId(ownerId)
                .workCaseId(WORK_CASE_ID)
                .transactionType("ESCROW_HOLD")
                .amount(AMOUNT)
                .availableBefore(700_000L)
                .availableAfter(400_000L)
                .lockedBefore(0L)
                .lockedAfter(AMOUNT)
                .referenceType("ESCROW")
                .referenceId(ESCROW_ID)
                .build();
    }

    private WalletTransactionSnapshot releaseLedger(long ownerId, long walletId) {
        boolean employer = ownerId == EMPLOYER_ID;
        return WalletTransactionSnapshot.builder()
                .id(2L)
                .walletId(walletId)
                .walletUserId(ownerId)
                .workCaseId(WORK_CASE_ID)
                .transactionType("ESCROW_RELEASE")
                .amount(AMOUNT)
                .availableBefore(employer ? 400_000L : 0L)
                .availableAfter(employer ? 400_000L : AMOUNT)
                .lockedBefore(employer ? AMOUNT : 0L)
                .lockedAfter(0L)
                .referenceType("ESCROW")
                .referenceId(ESCROW_ID)
                .build();
    }
}
