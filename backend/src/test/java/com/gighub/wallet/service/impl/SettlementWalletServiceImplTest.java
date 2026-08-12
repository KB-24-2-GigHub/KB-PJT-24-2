package com.gighub.wallet.service.impl;

import com.gighub.wallet.dto.WalletBalanceSnapshot;
import com.gighub.wallet.dto.WalletTransactionSnapshot;
import com.gighub.wallet.domain.EscrowStatus;
import com.gighub.wallet.exception.EscrowIntegrityException;
import com.gighub.wallet.mapper.WalletMapper;
import com.gighub.wallet.mapper.param.WalletBalanceUpdateParam;
import com.gighub.wallet.mapper.param.WalletTransactionParam;
import com.gighub.wallet.mapper.result.SettlementEscrowRow;
import com.gighub.wallet.service.SettlementWalletService.SettlementAmounts;
import com.gighub.wallet.service.SettlementWalletService.SettlementWalletLock;
import com.gighub.wallet.service.command.SettlementWalletCommand;
import com.gighub.wallet.service.result.SettlementEscrowSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void releaseResolvesKrwWalletsAndLocksByWalletIdForTheOriginalRoles() {
        SettlementWalletCommand command = command(8L, 2L);
        stubEscrowLock(command, holdLedger(8L, 20L));
        stubRelease(command, wallet(20L, 8L, 400_000L, AMOUNT),
                wallet(80L, 2L, 0L, 0L));

        SettlementEscrowSnapshot escrow = service.lockEscrow(command);
        SettlementWalletLock walletLock = service.lockPayoutWallets(command, ESCROW_ID);
        service.verifyHeldEscrow(command, escrow.getEscrowId(), walletLock);
        SettlementAmounts amounts = service.release(
                command, escrow.getEscrowId(), walletLock);

        assertEquals(AMOUNT, amounts.originalEscrowAmount());
        assertEquals(AMOUNT, amounts.workerPaidAmount());
        assertEquals(0L, amounts.ownerRefundAmount());
        assertEquals(
                amounts.originalEscrowAmount(),
                Math.addExact(amounts.workerPaidAmount(), amounts.ownerRefundAmount()));

        InOrder lockOrder = inOrder(walletMapper);
        lockOrder.verify(walletMapper).findSettlementEscrowForUpdate(WORK_CASE_ID);
        lockOrder.verify(walletMapper).resolveWalletId(8L, "KRW");
        lockOrder.verify(walletMapper).resolveWalletId(2L, "KRW");
        lockOrder.verify(walletMapper).getWalletSnapshotForUpdateByWalletId(20L);
        lockOrder.verify(walletMapper).getWalletSnapshotForUpdateByWalletId(80L);
        lockOrder.verify(walletMapper)
                .findEscrowHoldTransactionSnapshot(WORK_CASE_ID, ESCROW_ID);

        ArgumentCaptor<WalletBalanceUpdateParam> balanceCaptor =
                ArgumentCaptor.forClass(WalletBalanceUpdateParam.class);
        verify(walletMapper, times(2)).updateWalletBalanceByWalletId(
                balanceCaptor.capture());
        WalletBalanceUpdateParam employerBalance = balanceCaptor.getAllValues().get(0);
        WalletBalanceUpdateParam workerBalance = balanceCaptor.getAllValues().get(1);
        assertEquals(20L, employerBalance.getWalletId());
        assertEquals(AMOUNT, employerBalance.getLockedBefore());
        assertEquals(0L, employerBalance.getLockedAfter());
        assertEquals(80L, workerBalance.getWalletId());
        assertEquals(0L, workerBalance.getAvailableBefore());
        assertEquals(AMOUNT, workerBalance.getAvailableAfter());

        ArgumentCaptor<WalletTransactionParam> captor =
                ArgumentCaptor.forClass(WalletTransactionParam.class);
        verify(walletMapper, times(2)).insertWalletTransaction(captor.capture());
        WalletTransactionParam employer = captor.getAllValues().get(0);
        WalletTransactionParam worker = captor.getAllValues().get(1);
        assertEquals(20L, employer.getWalletId());
        assertEquals(AMOUNT, employer.getLockedBefore());
        assertEquals(0L, employer.getLockedAfter());
        assertEquals(command.getEmployerLedgerKey(), employer.getIdempotencyKey());
        assertEquals(80L, worker.getWalletId());
        assertEquals(0L, worker.getAvailableBefore());
        assertEquals(AMOUNT, worker.getAvailableAfter());
        assertEquals(command.getWorkerLedgerKey(), worker.getIdempotencyKey());
    }

    @Test
    void verifyCompletedPayoutAcceptsOnlyACompleteLedgerPairAndReleasedEscrow() {
        SettlementWalletCommand command = command(EMPLOYER_ID, WORKER_ID);
        stubWalletLocks(command, wallet(30L, EMPLOYER_ID, 400_000L, AMOUNT),
                wallet(40L, WORKER_ID, 0L, 0L));
        when(walletMapper.findSettlementTransactionByIdempotencyKeyForShare(
                command.getEmployerLedgerKey()))
                .thenReturn(releaseLedger(EMPLOYER_ID, 30L));
        when(walletMapper.findSettlementTransactionByIdempotencyKeyForShare(
                command.getWorkerLedgerKey()))
                .thenReturn(releaseLedger(WORKER_ID, 40L));
        when(walletMapper.findSettlementEscrowForUpdate(WORK_CASE_ID))
                .thenReturn(escrowRow(EscrowStatus.RELEASED));

        SettlementWalletLock walletLock = service.lockPayoutWallets(command, ESCROW_ID);
        service.verifyCompletedPayout(command, walletLock);
    }

    @Test
    void verifyCompletedPayoutRejectsACompletePairGap() {
        SettlementWalletCommand command = command(EMPLOYER_ID, WORKER_ID);
        stubWalletLocks(command, wallet(30L, EMPLOYER_ID, 400_000L, AMOUNT),
                wallet(40L, WORKER_ID, 0L, 0L));
        when(walletMapper.findSettlementTransactionByIdempotencyKeyForShare(
                command.getEmployerLedgerKey()))
                .thenReturn(releaseLedger(EMPLOYER_ID, 30L));
        SettlementWalletLock walletLock = service.lockPayoutWallets(command, ESCROW_ID);
        assertThrows(
                EscrowIntegrityException.class,
                () -> service.verifyCompletedPayout(command, walletLock));
    }

    @Test
    void releaseRejectsAChangedEmployerBeforeAnyMoneyMutation() {
        SettlementWalletCommand command = command(8L, WORKER_ID);
        stubWalletLocks(command, wallet(30L, 8L, 400_000L, AMOUNT),
                wallet(40L, WORKER_ID, 0L, 0L));
        when(walletMapper.findEscrowHoldTransactionSnapshot(WORK_CASE_ID, ESCROW_ID))
                .thenReturn(holdLedger(EMPLOYER_ID, 30L));

        SettlementWalletLock walletLock = service.lockPayoutWallets(command, ESCROW_ID);
        assertThrows(
                EscrowIntegrityException.class,
                () -> service.verifyHeldEscrow(command, ESCROW_ID, walletLock));

        verify(walletMapper, never()).releaseEscrow(anyLong());
        verify(walletMapper, never()).updateWalletBalanceByWalletId(any());
    }

    @Test
    void releaseTranslatesAConcurrentLedgerCollision() {
        SettlementWalletCommand command = command(EMPLOYER_ID, WORKER_ID);
        stubRelease(command, wallet(30L, EMPLOYER_ID, 400_000L, AMOUNT),
                wallet(40L, WORKER_ID, 0L, 0L));
        SettlementWalletLock walletLock = service.lockPayoutWallets(command, ESCROW_ID);
        when(walletMapper.insertWalletTransaction(any()))
                .thenReturn(1)
                .thenThrow(new DuplicateKeyException("concurrent ledger"));

        assertThrows(
                EscrowIntegrityException.class,
                () -> service.release(command, ESCROW_ID, walletLock));
        verify(walletMapper, times(2)).insertWalletTransaction(any());
    }

    @Test
    void lockEscrowReturnsNonHeldStateForTheSettlementPolicy() {
        SettlementWalletCommand command = command(EMPLOYER_ID, WORKER_ID);
        when(walletMapper.findSettlementEscrowForUpdate(WORK_CASE_ID))
                .thenReturn(escrowRow(EscrowStatus.RELEASED));

        assertEquals(EscrowStatus.RELEASED, service.lockEscrow(command).getStatus());

        verify(walletMapper, never()).getWalletSnapshotForUpdateByWalletId(anyLong());
    }

    private void stubRelease(
            SettlementWalletCommand command,
            WalletBalanceSnapshot employer,
            WalletBalanceSnapshot worker) {
        stubWalletLocks(command, employer, worker);
        when(walletMapper.releaseEscrow(WORK_CASE_ID)).thenReturn(1);
        when(walletMapper.updateWalletBalanceByWalletId(any())).thenReturn(1);
        when(walletMapper.insertWalletTransaction(any())).thenReturn(1);
    }

    private void stubWalletLocks(
            SettlementWalletCommand command,
            WalletBalanceSnapshot employer,
            WalletBalanceSnapshot worker) {
        long employerWalletId = employer.getWalletId();
        long workerWalletId = worker.getWalletId();
        when(walletMapper.resolveWalletId(command.getEmployerId(), "KRW"))
                .thenReturn(employerWalletId);
        when(walletMapper.resolveWalletId(command.getWorkerId(), "KRW"))
                .thenReturn(workerWalletId);
        long firstId = Math.min(employerWalletId, workerWalletId);
        long secondId = Math.max(employerWalletId, workerWalletId);
        WalletBalanceSnapshot first = firstId == employerWalletId ? employer : worker;
        WalletBalanceSnapshot second = secondId == employerWalletId ? employer : worker;
        when(walletMapper.getWalletSnapshotForUpdateByWalletId(firstId)).thenReturn(first);
        when(walletMapper.getWalletSnapshotForUpdateByWalletId(secondId)).thenReturn(second);
    }

    private void stubEscrowLock(
            SettlementWalletCommand command,
            WalletTransactionSnapshot hold) {
        when(walletMapper.findSettlementEscrowForUpdate(WORK_CASE_ID))
                .thenReturn(escrowRow(EscrowStatus.HELD));
        when(walletMapper.findEscrowHoldTransactionSnapshot(WORK_CASE_ID, ESCROW_ID))
                .thenReturn(hold);
    }

    private SettlementEscrowRow escrowRow(EscrowStatus status) {
        return new SettlementEscrowRow(ESCROW_ID, WORK_CASE_ID, AMOUNT, status);
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
