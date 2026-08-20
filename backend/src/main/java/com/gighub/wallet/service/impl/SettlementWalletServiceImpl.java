package com.gighub.wallet.service.impl;

import com.gighub.wallet.dto.WalletBalanceSnapshot;
import com.gighub.wallet.dto.WalletTransactionSnapshot;
import com.gighub.wallet.domain.Money;
import com.gighub.wallet.domain.WalletBalance;
import com.gighub.wallet.exception.EscrowIntegrityException;
import com.gighub.wallet.mapper.WalletMapper;
import com.gighub.wallet.mapper.param.WalletBalanceUpdateParam;
import com.gighub.wallet.mapper.param.WalletTransactionParam;
import com.gighub.wallet.service.SettlementWalletService;
import com.gighub.wallet.service.SettlementWalletService.SettlementWalletLock;
import com.gighub.wallet.service.command.NoShowRefundWalletCommand;
import com.gighub.wallet.service.command.SettlementWalletCommand;
import com.gighub.wallet.service.result.SettlementEscrowSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static com.gighub.wallet.service.impl.SettlementWalletIntegrityValidator.REF_ESCROW;
import static com.gighub.wallet.service.impl.SettlementWalletIntegrityValidator.TX_ESCROW_RELEASE;
import static com.gighub.wallet.service.impl.SettlementWalletIntegrityValidator.TX_ESCROW_REFUND;
import static com.gighub.wallet.service.impl.SettlementWalletIntegrityValidator.toSnapshot;
import static com.gighub.wallet.service.impl.SettlementWalletIntegrityValidator.validateCompletedEscrow;
import static com.gighub.wallet.service.impl.SettlementWalletIntegrityValidator.validateRefundedEscrow;
import static com.gighub.wallet.service.impl.SettlementWalletIntegrityValidator.validateEmployerReleaseLedgerInvariant;
import static com.gighub.wallet.service.impl.SettlementWalletIntegrityValidator.validateEscrowReference;
import static com.gighub.wallet.service.impl.SettlementWalletIntegrityValidator.validatePayoutRefundLedger;
import static com.gighub.wallet.service.impl.SettlementWalletIntegrityValidator.validateRefundLedger;
import static com.gighub.wallet.service.impl.SettlementWalletIntegrityValidator.validateHeldEscrowOwnership;
import static com.gighub.wallet.service.impl.SettlementWalletIntegrityValidator.validateReleaseLedger;
import static com.gighub.wallet.service.impl.SettlementWalletIntegrityValidator.validateWallet;
import static com.gighub.wallet.service.impl.SettlementWalletIntegrityValidator.validateWorkerReleaseLedgerInvariant;
import static com.gighub.wallet.service.impl.SettlementWalletIntegrityValidator.validateOwnerRefundLedgerInvariant;

/** Wallet owner Mapper와 자금·원장 무결성 검증을 한 participant에 둡니다. */
@Service
@RequiredArgsConstructor
public class SettlementWalletServiceImpl implements SettlementWalletService {

    private final WalletMapper walletMapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SettlementEscrowSnapshot lockEscrow(SettlementWalletCommand command) {
        return toSnapshot(walletMapper.findSettlementEscrowForUpdate(command.getWorkCaseId()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SettlementEscrowSnapshot lockRefundEscrow(NoShowRefundWalletCommand command) {
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
    public void verifyHeldRefundEscrow(
            NoShowRefundWalletCommand command,
            long escrowId,
            SettlementWalletLock walletLock) {
        RefundWalletLock lock = requireRefundLock(command, escrowId, walletLock);
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
        WalletTransactionSnapshot refund =
                walletMapper.findSettlementTransactionByIdempotencyKeyForShare(
                        command.getEmployerRefundLedgerKey());
        requireConditionalLedger(employer, command.getWorkerPaidAmount(), "고용주 지급");
        requireConditionalLedger(worker, command.getWorkerPaidAmount(), "근로자 지급");
        requireConditionalLedger(refund, command.getOwnerRefundAmount(), "고용주 환불");
        SettlementEscrowSnapshot escrow = toSnapshot(
                walletMapper.findSettlementEscrowForUpdate(command.getWorkCaseId()));
        long escrowId = validateCompletedEscrow(escrow, command, lock.escrowId());
        if (employer != null) {
            validateReleaseLedger(
                    employer,
                    lock.employerWalletId(),
                    command.getEmployerId(),
                    command,
                    command.getWorkerPaidAmount());
            validateEscrowReference(employer, escrowId);
            validateEmployerReleaseLedgerInvariant(
                    employer, command.getWorkerPaidAmount());
        }
        if (worker != null) {
            validateReleaseLedger(
                    worker,
                    lock.workerWalletId(),
                    command.getWorkerId(),
                    command,
                    command.getWorkerPaidAmount());
            validateEscrowReference(worker, escrowId);
            validateWorkerReleaseLedgerInvariant(worker, command.getWorkerPaidAmount());
        }
        if (refund != null) {
            validatePayoutRefundLedger(refund, lock.employerWalletId(), command);
            validateEscrowReference(refund, escrowId);
            validateOwnerRefundLedgerInvariant(refund, command.getOwnerRefundAmount());
        }
        validatePayoutLedgerChain(employer, worker, refund, lock);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void verifyCompletedRefund(
            NoShowRefundWalletCommand command, SettlementWalletLock walletLock) {
        RefundWalletLock lock = requireRefundLock(command, null, walletLock);
        WalletTransactionSnapshot owner =
                walletMapper.findSettlementTransactionByIdempotencyKeyForShare(
                        command.getEmployerLedgerKey());
        validateRefundLedger(owner, lock.employerWalletId(), command);
        SettlementEscrowSnapshot escrow = toSnapshot(
                walletMapper.findSettlementEscrowForUpdate(command.getWorkCaseId()));
        long escrowId = validateRefundedEscrow(escrow, command, lock.escrowId());
        validateEscrowReference(owner, escrowId);
        validateOwnerRefundLedgerInvariant(owner, command.getAmount());
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
                command.getWorkerPaidAmount(),
                command.getOwnerRefundAmount(),
                command.getEmployerLedgerKey(),
                command.getWorkerLedgerKey(),
                command.getEmployerRefundLedgerKey(),
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
    public SettlementWalletLock lockRefundWallet(
            NoShowRefundWalletCommand command, long escrowId) {
        if (escrowId <= 0) {
            throw new EscrowIntegrityException("NO_SHOW 환불 Escrow 식별자가 올바르지 않습니다.");
        }
        long employerWalletId = resolveWalletId(command.getEmployerId());
        WalletBalanceSnapshot employer =
                walletMapper.getWalletSnapshotForUpdateByWalletId(employerWalletId);
        validateWallet(employer, command.getEmployerId(), employerWalletId);
        return new RefundWalletLock(
                command.getWorkCaseId(),
                command.getEmployerId(),
                command.getAmount(),
                command.getEmployerLedgerKey(),
                escrowId,
                employerWalletId,
                employer.getAvailableBalance(),
                employer.getLockedBalance());
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
        validateSplit(command);
        WalletBalance employerAfterRelease = employerBefore;
        WalletBalance employerAfter = employerBefore;
        WalletBalance workerAfter;
        try {
            if (command.getWorkerPaidAmount() > 0) {
                Money payout = Money.krw(command.getWorkerPaidAmount());
                employerAfterRelease = employerBefore.release(payout);
                workerAfter = workerBefore.credit(payout);
            } else {
                workerAfter = workerBefore;
            }
            employerAfter = command.getOwnerRefundAmount() > 0
                    ? employerAfterRelease.refund(Money.krw(command.getOwnerRefundAmount()))
                    : employerAfterRelease;
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
        if (command.getWorkerPaidAmount() > 0
                && walletMapper.updateWalletBalanceByWalletId(WalletBalanceUpdateParam.of(
                        lock.workerWalletId(), workerBefore, workerAfter)) != 1) {
            throw new EscrowIntegrityException("근로자 지갑에 정산금을 반영하지 못했습니다.");
        }

        if (command.getWorkerPaidAmount() > 0) {
            insertLedger(WalletTransactionParam.builder()
                    .walletId(lock.employerWalletId())
                    .workCaseId(command.getWorkCaseId())
                    .transactionType(TX_ESCROW_RELEASE)
                    .amount(command.getWorkerPaidAmount())
                    .availableBefore(employerBefore.available())
                    .availableAfter(employerAfterRelease.available())
                    .lockedBefore(employerBefore.locked())
                    .lockedAfter(employerAfterRelease.locked())
                    .referenceType(REF_ESCROW)
                    .referenceId(escrowId)
                    .idempotencyKey(command.getEmployerLedgerKey())
                    .build(), "고용주 정산 지급 원장을 기록하지 못했습니다.");
            insertLedger(WalletTransactionParam.builder()
                    .walletId(lock.workerWalletId())
                    .workCaseId(command.getWorkCaseId())
                    .transactionType(TX_ESCROW_RELEASE)
                    .amount(command.getWorkerPaidAmount())
                    .availableBefore(workerBefore.available())
                    .availableAfter(workerAfter.available())
                    .lockedBefore(workerBefore.locked())
                    .lockedAfter(workerAfter.locked())
                    .referenceType(REF_ESCROW)
                    .referenceId(escrowId)
                    .idempotencyKey(command.getWorkerLedgerKey())
                    .build(), "근로자 정산 지급 원장을 기록하지 못했습니다.");
        }
        if (command.getOwnerRefundAmount() > 0) {
            insertLedger(WalletTransactionParam.builder()
                    .walletId(lock.employerWalletId())
                    .workCaseId(command.getWorkCaseId())
                    .transactionType(TX_ESCROW_REFUND)
                    .amount(command.getOwnerRefundAmount())
                    .availableBefore(employerAfterRelease.available())
                    .availableAfter(employerAfter.available())
                    .lockedBefore(employerAfterRelease.locked())
                    .lockedAfter(employerAfter.locked())
                    .referenceType(REF_ESCROW)
                    .referenceId(escrowId)
                    .idempotencyKey(command.getEmployerRefundLedgerKey())
                    .build(), "고용주 정산 환불 원장을 기록하지 못했습니다.");
        }

        return SettlementAmounts.split(
                command.getAmount(),
                command.getWorkerPaidAmount(),
                command.getOwnerRefundAmount());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SettlementAmounts refund(
            NoShowRefundWalletCommand command,
            long escrowId,
            SettlementWalletLock walletLock) {
        if (escrowId <= 0) {
            throw new EscrowIntegrityException("NO_SHOW 환불 Escrow 식별자가 올바르지 않습니다.");
        }
        RefundWalletLock lock = requireRefundLock(command, escrowId, walletLock);
        WalletBalance ownerBefore = WalletBalance.krw(
                lock.employerAvailable(), lock.employerLocked());
        WalletBalance ownerAfter;
        try {
            ownerAfter = ownerBefore.refund(Money.krw(command.getAmount()));
        } catch (WalletBalance.InsufficientBalanceException insufficient) {
            throw new EscrowIntegrityException(
                    "OWNER의 잠금 금액이 NO_SHOW 환불 금액보다 적습니다.", insufficient);
        } catch (ArithmeticException overflow) {
            throw new EscrowIntegrityException(
                    "NO_SHOW 환불 뒤 OWNER Wallet 금액이 허용 범위를 벗어납니다.", overflow);
        }
        if (walletMapper.refundEscrow(command.getWorkCaseId()) != 1) {
            throw new EscrowIntegrityException("Escrow를 환불 상태로 전이하지 못했습니다.");
        }
        if (walletMapper.updateWalletBalanceByWalletId(WalletBalanceUpdateParam.of(
                lock.employerWalletId(), ownerBefore, ownerAfter)) != 1) {
            throw new EscrowIntegrityException("OWNER Wallet에 환불 금액을 반영하지 못했습니다.");
        }
        insertLedger(WalletTransactionParam.builder()
                .walletId(lock.employerWalletId())
                .workCaseId(command.getWorkCaseId())
                .transactionType(TX_ESCROW_REFUND)
                .amount(command.getAmount())
                .availableBefore(ownerBefore.available())
                .availableAfter(ownerAfter.available())
                .lockedBefore(ownerBefore.locked())
                .lockedAfter(ownerAfter.locked())
                .referenceType(REF_ESCROW)
                .referenceId(escrowId)
                .idempotencyKey(command.getEmployerLedgerKey())
                .build(), "OWNER NO_SHOW 환불 원장을 기록하지 못했습니다.");
        return SettlementAmounts.fullRefund(command.getAmount());
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

    private PayoutWalletLock requirePayoutLock(
            SettlementWalletCommand command,
            Long expectedEscrowId,
            SettlementWalletLock walletLock) {
        if (!(walletLock instanceof PayoutWalletLock lock)
                || lock.workCaseId() != command.getWorkCaseId()
                || lock.employerUserId() != command.getEmployerId()
                || lock.workerUserId() != command.getWorkerId()
                || lock.amount() != command.getAmount()
                || lock.workerPaidAmount() != command.getWorkerPaidAmount()
                || lock.ownerRefundAmount() != command.getOwnerRefundAmount()
                || !Objects.equals(lock.employerLedgerKey(), command.getEmployerLedgerKey())
                || !Objects.equals(lock.workerLedgerKey(), command.getWorkerLedgerKey())
                || !Objects.equals(
                        lock.employerRefundLedgerKey(), command.getEmployerRefundLedgerKey())
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

    private RefundWalletLock requireRefundLock(
            NoShowRefundWalletCommand command,
            Long expectedEscrowId,
            SettlementWalletLock walletLock) {
        if (!(walletLock instanceof RefundWalletLock lock)
                || lock.workCaseId() != command.getWorkCaseId()
                || lock.employerUserId() != command.getEmployerId()
                || lock.amount() != command.getAmount()
                || !Objects.equals(lock.employerLedgerKey(), command.getEmployerLedgerKey())
                || (expectedEscrowId != null && lock.escrowId() != expectedEscrowId)
                || lock.employerWalletId() <= 0
                || lock.employerAvailable() < 0
                || lock.employerLocked() < 0) {
            throw new EscrowIntegrityException("잠긴 NO_SHOW 환불 Wallet Snapshot이 올바르지 않습니다.");
        }
        return lock;
    }

    private record PayoutWalletLock(
            long workCaseId,
            long employerUserId,
            long workerUserId,
            long amount,
            long workerPaidAmount,
            long ownerRefundAmount,
            String employerLedgerKey,
            String workerLedgerKey,
            String employerRefundLedgerKey,
            long escrowId,
            long employerWalletId,
            long employerAvailable,
            long employerLocked,
            long workerWalletId,
            long workerAvailable,
            long workerLocked) implements SettlementWalletLock {
    }

    private static void validateSplit(SettlementWalletCommand command) {
        if (command.getAmount() <= 0
                || command.getWorkerPaidAmount() < 0
                || command.getOwnerRefundAmount() < 0
                || command.getWorkerPaidAmount() > command.getAmount()
                || command.getOwnerRefundAmount() > command.getAmount()) {
            throw new EscrowIntegrityException("정산 분할 금액이 올바르지 않습니다.");
        }
        try {
            if (Math.addExact(
                    command.getWorkerPaidAmount(),
                    command.getOwnerRefundAmount()) != command.getAmount()) {
                throw new EscrowIntegrityException("정산 분할 금액이 예치액을 보존하지 않습니다.");
            }
        } catch (ArithmeticException overflow) {
            throw new EscrowIntegrityException("정산 분할 금액 합산이 허용 범위를 벗어납니다.", overflow);
        }
        if (command.getWorkerPaidAmount() > 0
                && (command.getEmployerLedgerKey() == null
                || command.getWorkerLedgerKey() == null)) {
            throw new EscrowIntegrityException("정산 지급 원장 키가 없습니다.");
        }
        if (command.getOwnerRefundAmount() > 0
                && command.getEmployerRefundLedgerKey() == null) {
            throw new EscrowIntegrityException("정산 환불 원장 키가 없습니다.");
        }
    }

    private static void requireConditionalLedger(
            WalletTransactionSnapshot snapshot, long expectedAmount, String legName) {
        if ((expectedAmount > 0) != (snapshot != null)) {
            throw new EscrowIntegrityException(legName + " 원장의 존재 여부가 분할 금액과 일치하지 않습니다.");
        }
    }

    private static void validatePayoutLedgerChain(
            WalletTransactionSnapshot employer,
            WalletTransactionSnapshot worker,
            WalletTransactionSnapshot refund,
            PayoutWalletLock lock) {
        WalletTransactionSnapshot firstOwner = employer != null ? employer : refund;
        if (firstOwner == null
                || firstOwner.getAvailableBefore() != lock.employerAvailable()
                || firstOwner.getLockedBefore() != lock.employerLocked()) {
            throw new EscrowIntegrityException("고용주 정산 원장의 시작 잔액이 잠금 Snapshot과 다릅니다.");
        }
        if (worker != null
                && (worker.getAvailableBefore() != lock.workerAvailable()
                || worker.getLockedBefore() != lock.workerLocked())) {
            throw new EscrowIntegrityException("근로자 정산 원장의 시작 잔액이 잠금 Snapshot과 다릅니다.");
        }
        if (employer != null && refund != null
                && (!employer.getAvailableAfter().equals(refund.getAvailableBefore())
                || !employer.getLockedAfter().equals(refund.getLockedBefore()))) {
            throw new EscrowIntegrityException("고용주 지급·환불 원장 잔액 연결이 끊겼습니다.");
        }
        Long referenceId = firstOwner.getReferenceId();
        if ((worker != null && !referenceId.equals(worker.getReferenceId()))
                || (refund != null && !referenceId.equals(refund.getReferenceId()))) {
            throw new EscrowIntegrityException("정산 원장들의 에스크로 참조가 일치하지 않습니다.");
        }
    }

    private record RefundWalletLock(
            long workCaseId,
            long employerUserId,
            long amount,
            String employerLedgerKey,
            long escrowId,
            long employerWalletId,
            long employerAvailable,
            long employerLocked) implements SettlementWalletLock {
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

}
