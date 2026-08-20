package com.gighub.wallet.service.impl;

import com.gighub.wallet.domain.EscrowStatus;
import com.gighub.wallet.dto.WalletBalanceSnapshot;
import com.gighub.wallet.dto.WalletTransactionSnapshot;
import com.gighub.wallet.exception.EscrowIntegrityException;
import com.gighub.wallet.service.command.SettlementWalletCommand;
import com.gighub.wallet.service.result.SettlementEscrowSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SettlementWalletIntegrityValidatorTest {

    private static final long WORK_CASE_ID = 71L;
    private static final long EMPLOYER_ID = 11L;
    private static final long WORKER_ID = 12L;
    private static final long EMPLOYER_WALLET_ID = 21L;
    private static final long ESCROW_ID = 31L;
    private static final long AMOUNT = 30_000L;

    @Test
    void validatesTheLockedWalletOwnerAndCompleteBalances() {
        WalletBalanceSnapshot wallet = WalletBalanceSnapshot.builder()
                .walletId(EMPLOYER_WALLET_ID)
                .userId(EMPLOYER_ID)
                .availableBalance(70_000L)
                .lockedBalance(AMOUNT)
                .build();

        assertDoesNotThrow(() -> SettlementWalletIntegrityValidator.validateWallet(
                wallet, EMPLOYER_ID, EMPLOYER_WALLET_ID));
        assertThrows(EscrowIntegrityException.class,
                () -> SettlementWalletIntegrityValidator.validateWallet(
                        wallet, WORKER_ID, EMPLOYER_WALLET_ID));
    }

    @Test
    void validatesTheHeldLedgerAgainstTheCurrentSettlementCommand() {
        WalletTransactionSnapshot hold = transaction(
                "ESCROW_HOLD", EMPLOYER_WALLET_ID, EMPLOYER_ID,
                100_000L, 70_000L, 0L, AMOUNT);

        assertDoesNotThrow(() ->
                SettlementWalletIntegrityValidator.validateHeldEscrowOwnership(
                        hold, command(), ESCROW_ID, EMPLOYER_WALLET_ID));
        assertThrows(EscrowIntegrityException.class,
                () -> SettlementWalletIntegrityValidator.validateHeldEscrowOwnership(
                        hold.toBuilder().amount(AMOUNT - 1).build(),
                        command(), ESCROW_ID, EMPLOYER_WALLET_ID));
    }

    @Test
    void validatesBothReleaseLedgerDirectionsWithoutTrustingDatabaseShapeAlone() {
        WalletTransactionSnapshot employer = transaction(
                "ESCROW_RELEASE", EMPLOYER_WALLET_ID, EMPLOYER_ID,
                70_000L, 70_000L, AMOUNT, 0L);
        WalletTransactionSnapshot worker = transaction(
                "ESCROW_RELEASE", 22L, WORKER_ID,
                10_000L, 40_000L, 0L, 0L);

        assertDoesNotThrow(() -> {
            SettlementWalletIntegrityValidator.validateReleaseLedger(
                    employer, EMPLOYER_WALLET_ID, EMPLOYER_ID, command(), AMOUNT);
            SettlementWalletIntegrityValidator.validateEmployerReleaseLedgerInvariant(
                    employer, AMOUNT);
            SettlementWalletIntegrityValidator.validateWorkerReleaseLedgerInvariant(
                    worker, AMOUNT);
        });
        assertThrows(EscrowIntegrityException.class,
                () -> SettlementWalletIntegrityValidator
                        .validateWorkerReleaseLedgerInvariant(
                                worker.toBuilder().availableAfter(39_999L).build(), AMOUNT));
    }

    @Test
    void acceptsOnlyTheReleasedEscrowBoundToTheLockedPayout() {
        SettlementEscrowSnapshot released = SettlementEscrowSnapshot.builder()
                .escrowId(ESCROW_ID)
                .workCaseId(WORK_CASE_ID)
                .amount(AMOUNT)
                .status(EscrowStatus.RELEASED)
                .build();

        assertEquals(
                ESCROW_ID,
                SettlementWalletIntegrityValidator.validateCompletedEscrow(
                        released, command(), ESCROW_ID));
        assertThrows(EscrowIntegrityException.class,
                () -> SettlementWalletIntegrityValidator.validateCompletedEscrow(
                        released.toBuilder().status(EscrowStatus.HELD).build(),
                        command(), ESCROW_ID));
    }

    private SettlementWalletCommand command() {
        return SettlementWalletCommand.builder()
                .workCaseId(WORK_CASE_ID)
                .employerId(EMPLOYER_ID)
                .workerId(WORKER_ID)
                .amount(AMOUNT)
                .workerPaidAmount(AMOUNT)
                .ownerRefundAmount(0L)
                .employerLedgerKey("SETTLE:71:OWNER")
                .workerLedgerKey("SETTLE:71:WORKER")
                .employerRefundLedgerKey("SETTLE:71:REFUND")
                .build();
    }

    private WalletTransactionSnapshot transaction(
            String type,
            long walletId,
            long userId,
            long availableBefore,
            long availableAfter,
            long lockedBefore,
            long lockedAfter) {
        return WalletTransactionSnapshot.builder()
                .id(41L)
                .walletId(walletId)
                .walletUserId(userId)
                .workCaseId(WORK_CASE_ID)
                .transactionType(type)
                .amount(AMOUNT)
                .availableBefore(availableBefore)
                .availableAfter(availableAfter)
                .lockedBefore(lockedBefore)
                .lockedAfter(lockedAfter)
                .referenceType("ESCROW")
                .referenceId(ESCROW_ID)
                .build();
    }
}
