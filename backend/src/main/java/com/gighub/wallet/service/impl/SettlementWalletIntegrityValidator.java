package com.gighub.wallet.service.impl;

import com.gighub.wallet.domain.EscrowStatus;
import com.gighub.wallet.dto.WalletBalanceSnapshot;
import com.gighub.wallet.dto.WalletTransactionSnapshot;
import com.gighub.wallet.exception.EscrowIntegrityException;
import com.gighub.wallet.mapper.result.SettlementEscrowRow;
import com.gighub.wallet.service.command.SettlementWalletCommand;
import com.gighub.wallet.service.command.NoShowRefundWalletCommand;
import com.gighub.wallet.service.result.SettlementEscrowSnapshot;

/**
 * 정산 지갑 처리에서 DB 접근 없이 스냅샷과 원장 불변식만 검증합니다.
 *
 * <p>DB CHECK는 저장 가능한 행 모양을 보장하지만, 현재 지급 명령과 잠긴 지갑·원장의
 * 소유권 및 금액이 일치하는지는 실행 시점에 다시 확인해야 합니다. 이 검증은 DB lifecycle
 * CHECK와 중복이 아니므로 서비스에서 생략하지 않습니다.</p>
 */
final class SettlementWalletIntegrityValidator {

    static final String TX_ESCROW_HOLD = "ESCROW_HOLD";
    static final String TX_ESCROW_RELEASE = "ESCROW_RELEASE";
    static final String TX_ESCROW_REFUND = "ESCROW_REFUND";
    static final String REF_ESCROW = "ESCROW";

    private SettlementWalletIntegrityValidator() {
    }

    static void validateWallet(
            WalletBalanceSnapshot snapshot, long expectedUserId, long expectedWalletId) {
        if (snapshot == null) {
            throw new EscrowIntegrityException("정산 대상 KRW 지갑을 찾을 수 없습니다.");
        }
        if (snapshot.getWalletId() == null
                || snapshot.getWalletId() != expectedWalletId
                || snapshot.getUserId() == null
                || snapshot.getUserId() != expectedUserId
                || snapshot.getAvailableBalance() == null
                || snapshot.getAvailableBalance() < 0
                || snapshot.getLockedBalance() == null
                || snapshot.getLockedBalance() < 0) {
            throw new EscrowIntegrityException("조회된 지갑 잔액 스냅샷이 올바르지 않습니다.");
        }
    }

    static void validateHeldEscrowOwnership(
            WalletTransactionSnapshot snapshot,
            SettlementWalletCommand command,
            long escrowId,
            long expectedWalletId) {
        if (snapshot == null
                || snapshot.getId() == null
                || snapshot.getId() <= 0
                || snapshot.getWalletId() == null
                || snapshot.getWalletId() != expectedWalletId
                || snapshot.getWalletUserId() == null
                || snapshot.getWalletUserId() != command.getEmployerId()
                || snapshot.getWorkCaseId() == null
                || snapshot.getWorkCaseId() != command.getWorkCaseId()
                || snapshot.getAmount() == null
                || snapshot.getAmount() != command.getAmount()
                || !TX_ESCROW_HOLD.equals(snapshot.getTransactionType())
                || !REF_ESCROW.equals(snapshot.getReferenceType())
                || snapshot.getReferenceId() == null
                || snapshot.getReferenceId() != escrowId) {
            throw new EscrowIntegrityException("예치 원장과 현재 정산 대상의 소유권이 일치하지 않습니다.");
        }
        validateHoldLedgerInvariant(snapshot, command.getAmount());
    }

    static void validateHeldEscrowOwnership(
            WalletTransactionSnapshot snapshot,
            NoShowRefundWalletCommand command,
            long escrowId,
            long expectedWalletId) {
        if (snapshot == null
                || snapshot.getId() == null
                || snapshot.getId() <= 0
                || snapshot.getWalletId() == null
                || snapshot.getWalletId() != expectedWalletId
                || snapshot.getWalletUserId() == null
                || snapshot.getWalletUserId() != command.getEmployerId()
                || snapshot.getWorkCaseId() == null
                || snapshot.getWorkCaseId() != command.getWorkCaseId()
                || snapshot.getAmount() == null
                || snapshot.getAmount() != command.getAmount()
                || !TX_ESCROW_HOLD.equals(snapshot.getTransactionType())
                || !REF_ESCROW.equals(snapshot.getReferenceType())
                || snapshot.getReferenceId() == null
                || snapshot.getReferenceId() != escrowId) {
            throw new EscrowIntegrityException(
                    "예치 원장과 NO_SHOW 환불 대상의 소유권이 일치하지 않습니다.");
        }
        validateHoldLedgerInvariant(snapshot, command.getAmount());
    }

    static void validateReleaseLedger(
            WalletTransactionSnapshot snapshot,
            long expectedWalletId,
            long expectedUserId,
            SettlementWalletCommand command) {
        if (snapshot.getId() == null
                || snapshot.getId() <= 0
                || snapshot.getWalletId() == null
                || snapshot.getWalletId() != expectedWalletId
                || snapshot.getWalletUserId() == null
                || snapshot.getWalletUserId() != expectedUserId
                || snapshot.getWorkCaseId() == null
                || snapshot.getWorkCaseId() != command.getWorkCaseId()
                || snapshot.getAmount() == null
                || snapshot.getAmount() != command.getAmount()
                || !TX_ESCROW_RELEASE.equals(snapshot.getTransactionType())
                || !REF_ESCROW.equals(snapshot.getReferenceType())
                || snapshot.getReferenceId() == null
                || snapshot.getReferenceId() <= 0) {
            throw new EscrowIntegrityException(
                    "정산 식별자 기반 원장이 현재 지급 결과와 일치하지 않습니다.");
        }
    }

    static void validateRefundLedger(
            WalletTransactionSnapshot snapshot,
            long expectedWalletId,
            NoShowRefundWalletCommand command) {
        if (snapshot == null
                || snapshot.getId() == null
                || snapshot.getId() <= 0
                || snapshot.getWalletId() == null
                || snapshot.getWalletId() != expectedWalletId
                || snapshot.getWalletUserId() == null
                || snapshot.getWalletUserId() != command.getEmployerId()
                || snapshot.getWorkCaseId() == null
                || snapshot.getWorkCaseId() != command.getWorkCaseId()
                || snapshot.getAmount() == null
                || snapshot.getAmount() != command.getAmount()
                || !TX_ESCROW_REFUND.equals(snapshot.getTransactionType())
                || !REF_ESCROW.equals(snapshot.getReferenceType())
                || snapshot.getReferenceId() == null
                || snapshot.getReferenceId() <= 0) {
            throw new EscrowIntegrityException(
                    "NO_SHOW 환불 원장이 현재 환불 결과와 일치하지 않습니다.");
        }
    }

    static long validateCompletedEscrow(
            SettlementEscrowSnapshot escrow,
            SettlementWalletCommand command,
            long expectedEscrowId) {
        if (escrow == null
                || escrow.getEscrowId() == null
                || escrow.getEscrowId() <= 0
                || escrow.getEscrowId() != expectedEscrowId
                || escrow.getStatus() != EscrowStatus.RELEASED
                || escrow.getAmount() == null
                || escrow.getAmount() != command.getAmount()) {
            throw new EscrowIntegrityException("완료된 정산과 에스크로 상태가 일치하지 않습니다.");
        }
        return escrow.getEscrowId();
    }

    static long validateRefundedEscrow(
            SettlementEscrowSnapshot escrow,
            NoShowRefundWalletCommand command,
            long expectedEscrowId) {
        if (escrow == null
                || escrow.getEscrowId() == null
                || escrow.getEscrowId() <= 0
                || escrow.getEscrowId() != expectedEscrowId
                || escrow.getStatus() != EscrowStatus.REFUNDED
                || escrow.getAmount() == null
                || escrow.getAmount() != command.getAmount()) {
            throw new EscrowIntegrityException(
                    "완료된 NO_SHOW 환불과 Escrow 상태가 일치하지 않습니다.");
        }
        return escrow.getEscrowId();
    }

    static void validateEscrowReference(WalletTransactionSnapshot snapshot, long escrowId) {
        if (snapshot.getReferenceId() != escrowId) {
            throw new EscrowIntegrityException("정산 원장이 다른 에스크로를 참조하고 있습니다.");
        }
    }

    static SettlementEscrowSnapshot toSnapshot(SettlementEscrowRow row) {
        if (row == null) {
            return null;
        }
        return SettlementEscrowSnapshot.builder()
                .escrowId(row.getEscrowId())
                .workCaseId(row.getWorkCaseId())
                .amount(row.getAmount())
                .status(row.getStatus())
                .build();
    }

    static void validateHoldLedgerInvariant(WalletTransactionSnapshot snapshot, long amount) {
        if (!hasCompleteBalances(snapshot)
                || !matchesSubtract(snapshot.getAvailableBefore(), amount, snapshot.getAvailableAfter())
                || !matchesAdd(snapshot.getLockedBefore(), amount, snapshot.getLockedAfter())) {
            throw new EscrowIntegrityException("저장된 에스크로 예치 원장 금액이 올바르지 않습니다.");
        }
    }

    static void validateEmployerReleaseLedgerInvariant(
            WalletTransactionSnapshot snapshot, long amount) {
        if (!hasCompleteBalances(snapshot)
                || !snapshot.getAvailableBefore().equals(snapshot.getAvailableAfter())
                || !matchesSubtract(snapshot.getLockedBefore(), amount, snapshot.getLockedAfter())) {
            throw new EscrowIntegrityException("저장된 고용주 정산 원장 금액이 올바르지 않습니다.");
        }
    }

    static void validateWorkerReleaseLedgerInvariant(
            WalletTransactionSnapshot snapshot, long amount) {
        if (!hasCompleteBalances(snapshot)
                || !matchesAdd(snapshot.getAvailableBefore(), amount, snapshot.getAvailableAfter())
                || !snapshot.getLockedBefore().equals(snapshot.getLockedAfter())) {
            throw new EscrowIntegrityException("저장된 근로자 정산 원장 금액이 올바르지 않습니다.");
        }
    }

    static void validateOwnerRefundLedgerInvariant(
            WalletTransactionSnapshot snapshot, long amount) {
        if (!hasCompleteBalances(snapshot)
                || !matchesAdd(snapshot.getAvailableBefore(), amount, snapshot.getAvailableAfter())
                || !matchesSubtract(snapshot.getLockedBefore(), amount, snapshot.getLockedAfter())) {
            throw new EscrowIntegrityException(
                    "저장된 OWNER 환불 원장 금액이 올바르지 않습니다.");
        }
    }

    private static boolean hasCompleteBalances(WalletTransactionSnapshot snapshot) {
        return snapshot.getAvailableBefore() != null
                && snapshot.getAvailableBefore() >= 0
                && snapshot.getAvailableAfter() != null
                && snapshot.getAvailableAfter() >= 0
                && snapshot.getLockedBefore() != null
                && snapshot.getLockedBefore() >= 0
                && snapshot.getLockedAfter() != null
                && snapshot.getLockedAfter() >= 0;
    }

    private static boolean matchesAdd(long before, long amount, long after) {
        try {
            return Math.addExact(before, amount) == after;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private static boolean matchesSubtract(long before, long amount, long after) {
        try {
            return Math.subtractExact(before, amount) == after;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }
}
