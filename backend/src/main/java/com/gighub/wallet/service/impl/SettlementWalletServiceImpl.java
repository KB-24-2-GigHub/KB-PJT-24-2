package com.gighub.wallet.service.impl;

import com.gighub.wallet.dto.WalletBalanceSnapshot;
import com.gighub.wallet.dto.WalletTransactionSnapshot;
import com.gighub.wallet.domain.EscrowStatus;
import com.gighub.wallet.domain.Money;
import com.gighub.wallet.domain.WalletBalance;
import com.gighub.wallet.exception.EscrowIntegrityException;
import com.gighub.wallet.mapper.WalletMapper;
import com.gighub.wallet.mapper.param.WalletBalanceUpdateParam;
import com.gighub.wallet.mapper.param.WalletTransactionParam;
import com.gighub.wallet.mapper.result.SettlementEscrowRow;
import com.gighub.wallet.service.SettlementWalletService;
import com.gighub.wallet.service.SettlementWalletService.SettlementWalletLock;
import com.gighub.wallet.service.command.SettlementWalletCommand;
import com.gighub.wallet.service.result.SettlementEscrowSnapshot;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Wallet owner Mapper와 자금·원장 무결성 검증을 한 participant에 둡니다. */
@Service
public class SettlementWalletServiceImpl implements SettlementWalletService {

    private static final String TX_ESCROW_HOLD = "ESCROW_HOLD";
    private static final String TX_ESCROW_RELEASE = "ESCROW_RELEASE";
    private static final String REF_ESCROW = "ESCROW";

    private final WalletMapper walletMapper;

    public SettlementWalletServiceImpl(WalletMapper walletMapper) {
        this.walletMapper = walletMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SettlementEscrowSnapshot lockEscrow(SettlementWalletCommand command) {
        return toSnapshot(walletMapper.findSettlementEscrowForUpdate(command.getWorkCaseId()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void verifyHeldEscrow(
            SettlementWalletCommand command,
            long escrowId,
            SettlementWalletLock walletLock) {
        PayoutWalletLock lock = requirePayoutLock(command, escrowId, walletLock);
        validateHeldEscrowOwnership(
                walletMapper.findEscrowHoldTransactionSnapshot(
                        command.getWorkCaseId(), escrowId),
                command,
                escrowId,
                lock.employerWalletId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void verifyCompletedPayout(
            SettlementWalletCommand command, SettlementWalletLock walletLock) {
        PayoutWalletLock lock = requirePayoutLock(command, null, walletLock);
        WalletTransactionSnapshot employer =
                walletMapper.findSettlementTransactionByIdempotencyKeyForShare(
                        command.getEmployerLedgerKey());
        WalletTransactionSnapshot worker =
                walletMapper.findSettlementTransactionByIdempotencyKeyForShare(
                        command.getWorkerLedgerKey());
        if (employer == null || worker == null) {
            throw new EscrowIntegrityException("정산 원장 쌍이 완전하지 않습니다.");
        }
        validateReleaseLedger(
                employer, lock.employerWalletId(), command.getEmployerId(), command);
        validateReleaseLedger(
                worker, lock.workerWalletId(), command.getWorkerId(), command);
        if (!employer.getReferenceId().equals(worker.getReferenceId())) {
            throw new EscrowIntegrityException("정산 원장 쌍의 에스크로 참조가 일치하지 않습니다.");
        }
        SettlementEscrowSnapshot escrow = toSnapshot(
                walletMapper.findSettlementEscrowForUpdate(command.getWorkCaseId()));
        if (escrow == null
                || escrow.getEscrowId() == null
                || escrow.getEscrowId() <= 0
                || escrow.getEscrowId() != lock.escrowId()
                || escrow.getStatus() != EscrowStatus.RELEASED
                || escrow.getAmount() == null
                || escrow.getAmount() != command.getAmount()) {
            throw new EscrowIntegrityException("완료된 정산과 에스크로 상태가 일치하지 않습니다.");
        }
        long escrowId = escrow.getEscrowId();
        validateEscrowReference(employer, escrowId);
        validateEscrowReference(worker, escrowId);
        validateEmployerReleaseLedgerInvariant(employer, command.getAmount());
        validateWorkerReleaseLedgerInvariant(worker, command.getAmount());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SettlementWalletLock lockPayoutWallets(
            SettlementWalletCommand command, long escrowId) {
        if (escrowId <= 0) {
            throw new EscrowIntegrityException("정산 대상 에스크로 식별자가 올바르지 않습니다.");
        }
        long employerWalletId = resolveWalletId(command.getEmployerId());
        long workerWalletId = resolveWalletId(command.getWorkerId());
        if (employerWalletId == workerWalletId) {
            throw new EscrowIntegrityException("고용주와 근로자 지갑이 분리되지 않았습니다.");
        }

        Map<Long, WalletBalanceSnapshot> wallets = lockWalletsInOrder(
                employerWalletId,
                command.getEmployerId(),
                workerWalletId,
                command.getWorkerId());
        WalletBalanceSnapshot employer = wallets.get(employerWalletId);
        WalletBalanceSnapshot worker = wallets.get(workerWalletId);
        return new PayoutWalletLock(
                command.getWorkCaseId(),
                command.getEmployerId(),
                command.getWorkerId(),
                command.getAmount(),
                command.getEmployerLedgerKey(),
                command.getWorkerLedgerKey(),
                escrowId,
                employerWalletId,
                employer.getAvailableBalance(),
                employer.getLockedBalance(),
                workerWalletId,
                worker.getAvailableBalance(),
                worker.getLockedBalance());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SettlementAmounts release(
            SettlementWalletCommand command,
            long escrowId,
            SettlementWalletLock walletLock) {
        if (escrowId <= 0) {
            throw new EscrowIntegrityException("정산 대상 에스크로 식별자가 올바르지 않습니다.");
        }
        PayoutWalletLock lock = requirePayoutLock(command, escrowId, walletLock);
        WalletBalance employerBefore = WalletBalance.krw(
                lock.employerAvailable(), lock.employerLocked());
        WalletBalance workerBefore = WalletBalance.krw(
                lock.workerAvailable(), lock.workerLocked());
        Money amount = Money.krw(command.getAmount());
        WalletBalance employerAfter;
        WalletBalance workerAfter;
        try {
            employerAfter = employerBefore.release(amount);
            workerAfter = workerBefore.credit(amount);
        } catch (WalletBalance.InsufficientBalanceException insufficient) {
            throw new EscrowIntegrityException(
                    "고용주의 잠금 금액이 정산 금액보다 적습니다.", insufficient);
        } catch (ArithmeticException overflow) {
            throw new EscrowIntegrityException(
                    "정산 후 지갑 금액이 허용 범위를 벗어납니다.", overflow);
        }
        if (walletMapper.releaseEscrow(command.getWorkCaseId()) != 1) {
            throw new EscrowIntegrityException("에스크로 지급 상태를 반영하지 못했습니다.");
        }
        if (walletMapper.updateWalletBalanceByWalletId(WalletBalanceUpdateParam.of(
                lock.employerWalletId(), employerBefore, employerAfter)) != 1) {
            throw new EscrowIntegrityException("고용주 잠금 금액을 차감하지 못했습니다.");
        }
        if (walletMapper.updateWalletBalanceByWalletId(WalletBalanceUpdateParam.of(
                lock.workerWalletId(), workerBefore, workerAfter)) != 1) {
            throw new EscrowIntegrityException("근로자 지갑에 정산금을 반영하지 못했습니다.");
        }

        insertLedger(WalletTransactionParam.builder()
                .walletId(lock.employerWalletId())
                .workCaseId(command.getWorkCaseId())
                .transactionType(TX_ESCROW_RELEASE)
                .amount(command.getAmount())
                .availableBefore(employerBefore.available())
                .availableAfter(employerAfter.available())
                .lockedBefore(employerBefore.locked())
                .lockedAfter(employerAfter.locked())
                .referenceType(REF_ESCROW)
                .referenceId(escrowId)
                .idempotencyKey(command.getEmployerLedgerKey())
                .build(), "고용주 정산 원장을 기록하지 못했습니다.");
        insertLedger(WalletTransactionParam.builder()
                .walletId(lock.workerWalletId())
                .workCaseId(command.getWorkCaseId())
                .transactionType(TX_ESCROW_RELEASE)
                .amount(command.getAmount())
                .availableBefore(workerBefore.available())
                .availableAfter(workerAfter.available())
                .lockedBefore(workerBefore.locked())
                .lockedAfter(workerAfter.locked())
                .referenceType(REF_ESCROW)
                .referenceId(escrowId)
                .idempotencyKey(command.getWorkerLedgerKey())
                .build(), "근로자 정산 원장을 기록하지 못했습니다.");

        // 응답 금액은 요청을 다시 계산하지 않고, 방금 검증·반영한 전액 지급 결과에서 만듭니다.
        return SettlementAmounts.fullPayout(command.getAmount());
    }

    private long resolveWalletId(long userId) {
        Long walletId = walletMapper.resolveWalletId(userId, Money.KRW);
        if (walletId == null || walletId <= 0) {
            throw new EscrowIntegrityException("정산 대상 KRW 지갑을 찾을 수 없습니다.");
        }
        return walletId;
    }

    private Map<Long, WalletBalanceSnapshot> lockWalletsInOrder(
            long employerWalletId,
            long employerId,
            long workerWalletId,
            long workerId) {
        long firstId = Math.min(employerWalletId, workerWalletId);
        long secondId = Math.max(employerWalletId, workerWalletId);
        Map<Long, WalletBalanceSnapshot> snapshots = new HashMap<>();
        WalletBalanceSnapshot first = walletMapper.getWalletSnapshotForUpdateByWalletId(firstId);
        validateWallet(first, firstId == employerWalletId ? employerId : workerId, firstId);
        snapshots.put(firstId, first);
        WalletBalanceSnapshot second = walletMapper.getWalletSnapshotForUpdateByWalletId(secondId);
        validateWallet(second, secondId == employerWalletId ? employerId : workerId, secondId);
        snapshots.put(secondId, second);
        return snapshots;
    }

    private void validateWallet(
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

    private PayoutWalletLock requirePayoutLock(
            SettlementWalletCommand command,
            Long expectedEscrowId,
            SettlementWalletLock walletLock) {
        if (!(walletLock instanceof PayoutWalletLock lock)
                || lock.workCaseId() != command.getWorkCaseId()
                || lock.employerUserId() != command.getEmployerId()
                || lock.workerUserId() != command.getWorkerId()
                || lock.amount() != command.getAmount()
                || !Objects.equals(lock.employerLedgerKey(), command.getEmployerLedgerKey())
                || !Objects.equals(lock.workerLedgerKey(), command.getWorkerLedgerKey())
                || (expectedEscrowId != null && lock.escrowId() != expectedEscrowId)
                || lock.employerWalletId() <= 0
                || lock.workerWalletId() <= 0
                || lock.employerWalletId() == lock.workerWalletId()
                || lock.employerAvailable() < 0
                || lock.employerLocked() < 0
                || lock.workerAvailable() < 0
                || lock.workerLocked() < 0) {
            throw new EscrowIntegrityException("잠긴 정산 지갑 Snapshot이 올바르지 않습니다.");
        }
        return lock;
    }

    private record PayoutWalletLock(
            long workCaseId,
            long employerUserId,
            long workerUserId,
            long amount,
            String employerLedgerKey,
            String workerLedgerKey,
            long escrowId,
            long employerWalletId,
            long employerAvailable,
            long employerLocked,
            long workerWalletId,
            long workerAvailable,
            long workerLocked) implements SettlementWalletLock {
    }

    private void validateHeldEscrowOwnership(
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

    private void validateReleaseLedger(
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

    private void validateEscrowReference(WalletTransactionSnapshot snapshot, long escrowId) {
        if (snapshot.getReferenceId() != escrowId) {
            throw new EscrowIntegrityException("정산 원장이 다른 에스크로를 참조하고 있습니다.");
        }
    }

    private SettlementEscrowSnapshot toSnapshot(SettlementEscrowRow row) {
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
            throw new EscrowIntegrityException(
                    "정산 식별자 기반 원장이 이미 존재해 지급 무결성을 보장할 수 없습니다.",
                    duplicate);
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
