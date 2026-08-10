package com.gighub.wallet.service.impl;

import com.gighub.wallet.dto.WalletBalanceSnapshot;
import com.gighub.wallet.dto.WalletTransactionSnapshot;
import com.gighub.wallet.exception.EscrowIntegrityException;
import com.gighub.wallet.exception.IdempotencyKeyReusedException;
import com.gighub.wallet.exception.InvalidEscrowStateException;
import com.gighub.wallet.mapper.WalletMapper;
import com.gighub.wallet.mapper.param.WalletTransactionParam;
import com.gighub.wallet.service.SettlementWalletService;
import com.gighub.wallet.service.command.SettlementWalletCommand;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/** Wallet owner Mapper와 자금·원장 무결성 검증을 한 participant에 둡니다. */
@Service
public class SettlementWalletServiceImpl implements SettlementWalletService {

    private static final String ESCROW_HELD = "HELD";
    private static final String ESCROW_RELEASED = "RELEASED";
    private static final String TX_ESCROW_HOLD = "ESCROW_HOLD";
    private static final String TX_ESCROW_RELEASE = "ESCROW_RELEASE";
    private static final String REF_ESCROW = "ESCROW";

    private final WalletMapper walletMapper;

    public SettlementWalletServiceImpl(WalletMapper walletMapper) {
        this.walletMapper = walletMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean verifyReplay(SettlementWalletCommand command) {
        WalletTransactionSnapshot employer =
                walletMapper.findSettlementTransactionByIdempotencyKeyForShare(
                        command.getEmployerLedgerKey());
        WalletTransactionSnapshot worker =
                walletMapper.findSettlementTransactionByIdempotencyKeyForShare(
                        command.getWorkerLedgerKey());
        if (employer == null && worker == null) {
            return false;
        }
        if (employer == null || worker == null) {
            throw new EscrowIntegrityException("정산 원장 쌍이 완전하지 않습니다.");
        }
        validateReleaseLedger(employer, command.getEmployerId(), command);
        validateReleaseLedger(worker, command.getWorkerId(), command);
        if (!employer.getReferenceId().equals(worker.getReferenceId())) {
            throw new EscrowIntegrityException("정산 원장 쌍의 에스크로 참조가 일치하지 않습니다.");
        }
        long escrowId = requireEscrowId(command.getWorkCaseId());
        validateEscrowReference(employer, escrowId);
        validateEscrowReference(worker, escrowId);
        validateEmployerReleaseLedgerInvariant(employer, command.getAmount());
        validateWorkerReleaseLedgerInvariant(worker, command.getAmount());
        if (!ESCROW_RELEASED.equals(
                walletMapper.getEscrowStatusForUpdate(command.getWorkCaseId()))) {
            throw new EscrowIntegrityException("완료된 정산과 에스크로 상태가 일치하지 않습니다.");
        }
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(SettlementWalletCommand command) {
        Map<Long, WalletBalanceSnapshot> wallets = lockWalletsInOrder(
                command.getEmployerId(), command.getWorkerId());
        WalletBalanceSnapshot employer = wallets.get(command.getEmployerId());
        WalletBalanceSnapshot worker = wallets.get(command.getWorkerId());

        if (!ESCROW_HELD.equals(
                walletMapper.getEscrowStatusForUpdate(command.getWorkCaseId()))) {
            throw new InvalidEscrowStateException("정산 가능한 에스크로가 없습니다.");
        }
        Long storedAmount = walletMapper.getHeldEscrowAmount(command.getWorkCaseId());
        if (storedAmount == null || storedAmount != command.getAmount()) {
            throw new EscrowIntegrityException("정산 원장, 에스크로, 약정 임금의 금액이 일치하지 않습니다.");
        }
        if (employer.getLockedBalance() < command.getAmount()) {
            throw new EscrowIntegrityException("고용주의 잠금 금액이 정산 금액보다 적습니다.");
        }

        long employerLockedAfter = subtractExactly(
                employer.getLockedBalance(), command.getAmount(),
                "정산 후 고용주 잠금 금액이 허용 범위를 벗어납니다.");
        long workerAvailableAfter = addExactly(
                worker.getAvailableBalance(), command.getAmount(),
                "정산 후 근로자 지갑 금액이 허용 범위를 벗어납니다.");
        long escrowId = requireEscrowId(command.getWorkCaseId());
        validateHeldEscrowOwnership(
                walletMapper.findEscrowHoldTransactionSnapshot(
                        command.getWorkCaseId(), escrowId),
                command,
                escrowId);

        if (walletMapper.releaseEscrow(command.getWorkCaseId()) != 1) {
            throw new EscrowIntegrityException("에스크로 지급 상태를 반영하지 못했습니다.");
        }
        if (walletMapper.releaseLockedFunds(
                command.getEmployerId(), command.getAmount()) != 1) {
            throw new EscrowIntegrityException("고용주 잠금 금액을 차감하지 못했습니다.");
        }
        if (walletMapper.addAvailableBalance(
                command.getWorkerId(), command.getAmount()) != 1) {
            throw new EscrowIntegrityException("근로자 지갑에 정산금을 반영하지 못했습니다.");
        }

        insertLedger(WalletTransactionParam.builder()
                .walletId(employer.getWalletId())
                .workCaseId(command.getWorkCaseId())
                .transactionType(TX_ESCROW_RELEASE)
                .amount(command.getAmount())
                .availableBefore(employer.getAvailableBalance())
                .availableAfter(employer.getAvailableBalance())
                .lockedBefore(employer.getLockedBalance())
                .lockedAfter(employerLockedAfter)
                .referenceType(REF_ESCROW)
                .referenceId(escrowId)
                .idempotencyKey(command.getEmployerLedgerKey())
                .build(), "고용주 정산 원장을 기록하지 못했습니다.");
        insertLedger(WalletTransactionParam.builder()
                .walletId(worker.getWalletId())
                .workCaseId(command.getWorkCaseId())
                .transactionType(TX_ESCROW_RELEASE)
                .amount(command.getAmount())
                .availableBefore(worker.getAvailableBalance())
                .availableAfter(workerAvailableAfter)
                .lockedBefore(worker.getLockedBalance())
                .lockedAfter(worker.getLockedBalance())
                .referenceType(REF_ESCROW)
                .referenceId(escrowId)
                .idempotencyKey(command.getWorkerLedgerKey())
                .build(), "근로자 정산 원장을 기록하지 못했습니다.");
    }

    private Map<Long, WalletBalanceSnapshot> lockWalletsInOrder(long employerId, long workerId) {
        long firstId = Math.min(employerId, workerId);
        long secondId = Math.max(employerId, workerId);
        Map<Long, WalletBalanceSnapshot> snapshots = new HashMap<>();
        WalletBalanceSnapshot first = walletMapper.getWalletSnapshotForUpdate(firstId);
        validateWallet(first, firstId);
        snapshots.put(firstId, first);
        WalletBalanceSnapshot second = walletMapper.getWalletSnapshotForUpdate(secondId);
        validateWallet(second, secondId);
        snapshots.put(secondId, second);
        return snapshots;
    }

    private void validateWallet(WalletBalanceSnapshot snapshot, long expectedUserId) {
        if (snapshot == null) {
            throw new InvalidEscrowStateException("정산 대상 지갑을 찾을 수 없습니다.");
        }
        if (snapshot.getWalletId() == null
                || snapshot.getWalletId() <= 0
                || snapshot.getUserId() == null
                || snapshot.getUserId() != expectedUserId
                || snapshot.getAvailableBalance() == null
                || snapshot.getAvailableBalance() < 0
                || snapshot.getLockedBalance() == null
                || snapshot.getLockedBalance() < 0) {
            throw new EscrowIntegrityException("조회된 지갑 잔액 스냅샷이 올바르지 않습니다.");
        }
    }

    private void validateHeldEscrowOwnership(
            WalletTransactionSnapshot snapshot,
            SettlementWalletCommand command,
            long escrowId) {
        if (snapshot == null
                || snapshot.getId() == null
                || snapshot.getId() <= 0
                || snapshot.getWalletId() == null
                || snapshot.getWalletId() <= 0
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

    private void validateReleaseLedger(
            WalletTransactionSnapshot snapshot,
            long expectedUserId,
            SettlementWalletCommand command) {
        if (snapshot.getId() == null
                || snapshot.getId() <= 0
                || snapshot.getWalletId() == null
                || snapshot.getWalletId() <= 0
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
            throw new IdempotencyKeyReusedException(
                    "같은 멱등 키로 다른 정산 요청이 접수되었습니다.");
        }
    }

    private long requireEscrowId(long workCaseId) {
        Long escrowId = walletMapper.getEscrowIdByWorkCaseId(workCaseId);
        if (escrowId == null || escrowId <= 0) {
            throw new EscrowIntegrityException("근무 건의 에스크로를 찾을 수 없습니다.");
        }
        return escrowId;
    }

    private void validateEscrowReference(WalletTransactionSnapshot snapshot, long escrowId) {
        if (snapshot.getReferenceId() != escrowId) {
            throw new EscrowIntegrityException("정산 원장이 다른 에스크로를 참조하고 있습니다.");
        }
    }

    private void validateHoldLedgerInvariant(WalletTransactionSnapshot snapshot, long amount) {
        if (!hasCompleteBalances(snapshot)
                || !matchesSubtract(snapshot.getAvailableBefore(), amount, snapshot.getAvailableAfter())
                || !matchesAdd(snapshot.getLockedBefore(), amount, snapshot.getLockedAfter())) {
            throw new EscrowIntegrityException("저장된 에스크로 예치 원장 금액이 올바르지 않습니다.");
        }
    }

    private void validateEmployerReleaseLedgerInvariant(
            WalletTransactionSnapshot snapshot, long amount) {
        if (!hasCompleteBalances(snapshot)
                || !snapshot.getAvailableBefore().equals(snapshot.getAvailableAfter())
                || !matchesSubtract(snapshot.getLockedBefore(), amount, snapshot.getLockedAfter())) {
            throw new EscrowIntegrityException("저장된 고용주 정산 원장 금액이 올바르지 않습니다.");
        }
    }

    private void validateWorkerReleaseLedgerInvariant(
            WalletTransactionSnapshot snapshot, long amount) {
        if (!hasCompleteBalances(snapshot)
                || !matchesAdd(snapshot.getAvailableBefore(), amount, snapshot.getAvailableAfter())
                || !snapshot.getLockedBefore().equals(snapshot.getLockedAfter())) {
            throw new EscrowIntegrityException("저장된 근로자 정산 원장 금액이 올바르지 않습니다.");
        }
    }

    private boolean hasCompleteBalances(WalletTransactionSnapshot snapshot) {
        return snapshot.getAvailableBefore() != null
                && snapshot.getAvailableBefore() >= 0
                && snapshot.getAvailableAfter() != null
                && snapshot.getAvailableAfter() >= 0
                && snapshot.getLockedBefore() != null
                && snapshot.getLockedBefore() >= 0
                && snapshot.getLockedAfter() != null
                && snapshot.getLockedAfter() >= 0;
    }

    private void insertLedger(WalletTransactionParam param, String message) {
        try {
            if (walletMapper.insertWalletTransaction(param) != 1) {
                throw new EscrowIntegrityException(message);
            }
        } catch (DuplicateKeyException duplicate) {
            throw new IdempotencyKeyReusedException(
                    "같은 멱등 키로 다른 정산 요청이 동시에 접수되었습니다.");
        }
    }

    private long addExactly(long left, long right, String message) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new EscrowIntegrityException(message);
        }
    }

    private long subtractExactly(long left, long right, String message) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new EscrowIntegrityException(message);
        }
    }

    private boolean matchesAdd(long before, long amount, long after) {
        try {
            return Math.addExact(before, amount) == after;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private boolean matchesSubtract(long before, long amount, long after) {
        try {
            return Math.subtractExact(before, amount) == after;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }
}
