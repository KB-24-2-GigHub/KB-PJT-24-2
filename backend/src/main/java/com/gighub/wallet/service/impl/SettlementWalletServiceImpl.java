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
import static com.gighub.wallet.service.impl.SettlementWalletIntegrityValidator.toSnapshot;
import static com.gighub.wallet.service.impl.SettlementWalletIntegrityValidator.validateCompletedEscrow;
import static com.gighub.wallet.service.impl.SettlementWalletIntegrityValidator.validateEmployerReleaseLedgerInvariant;
import static com.gighub.wallet.service.impl.SettlementWalletIntegrityValidator.validateEscrowReference;
import static com.gighub.wallet.service.impl.SettlementWalletIntegrityValidator.validateHeldEscrowOwnership;
import static com.gighub.wallet.service.impl.SettlementWalletIntegrityValidator.validateReleaseLedger;
import static com.gighub.wallet.service.impl.SettlementWalletIntegrityValidator.validateWallet;
import static com.gighub.wallet.service.impl.SettlementWalletIntegrityValidator.validateWorkerReleaseLedgerInvariant;

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
        long escrowId = validateCompletedEscrow(escrow, command, lock.escrowId());
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
