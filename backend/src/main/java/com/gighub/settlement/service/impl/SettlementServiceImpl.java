package com.gighub.settlement.service.impl;

import com.gighub.settlement.domain.SettlementStatus;
import com.gighub.settlement.dto.SettlementSnapshot;
import com.gighub.settlement.mapper.SettlementLedgerMapper;
import com.gighub.settlement.mapper.SettlementMapper;
import com.gighub.settlement.service.SettlementService;
import com.gighub.settlement.service.command.SettlementApproveCommand;
import com.gighub.settlement.service.result.SettlementResult;
import com.gighub.wallet.dto.WalletBalanceSnapshot;
import com.gighub.wallet.dto.WalletTransactionSnapshot;
import com.gighub.wallet.exception.EscrowAccessDeniedException;
import com.gighub.wallet.exception.EscrowIntegrityException;
import com.gighub.wallet.exception.IdempotencyKeyReusedException;
import com.gighub.wallet.exception.InvalidEscrowStateException;
import com.gighub.wallet.idempotency.WalletIdempotencyKeys;
import com.gighub.wallet.mapper.WalletMapper;
import com.gighub.wallet.mapper.param.WalletTransactionParam;
import com.gighub.work.domain.WorkCaseDecision;
import com.gighub.work.domain.WorkCasePolicy;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.mapper.WorkMapper;
import com.gighub.work.contract.WorkCaseEscrowSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SettlementServiceImpl implements SettlementService {

    private static final String ESCROW_HELD = "HELD";
    private static final String ESCROW_RELEASED = "RELEASED";
    private static final String TX_ESCROW_HOLD = "ESCROW_HOLD";
    private static final String TX_ESCROW_RELEASE = "ESCROW_RELEASE";
    private static final String REF_ESCROW = "ESCROW";

    private final SettlementMapper settlementMapper;
    private final SettlementLedgerMapper settlementLedgerMapper;
    private final WalletMapper walletMapper;
    private final WorkMapper workMapper;

    @Override
    @Transactional
    public SettlementResult approve(SettlementApproveCommand command) {
        validateCommand(command);
        String employerLedgerKey =
                WalletIdempotencyKeys.escrowReleaseEmployer(command.getIdempotencyKey());
        String workerLedgerKey =
                WalletIdempotencyKeys.escrowReleaseWorker(command.getIdempotencyKey());

        WorkCaseEscrowSnapshot context =
                workMapper.getEscrowContextForUpdate(command.getWorkCaseId());
        validateContext(context, command);

        SettlementSnapshot settlement =
                settlementMapper.findByWorkCaseIdForUpdate(command.getWorkCaseId());
        validateSettlementIdentity(settlement, context);

        boolean completedSettlement =
                SettlementStatus.COMPLETED == settlement.getStatus();
        WalletTransactionSnapshot existingEmployerLedger =
                findReleaseLedger(employerLedgerKey, completedSettlement);
        WalletTransactionSnapshot existingWorkerLedger =
                findReleaseLedger(workerLedgerKey, completedSettlement);
        if (existingEmployerLedger != null || existingWorkerLedger != null) {
            return replay(
                    settlement,
                    context,
                    existingEmployerLedger,
                    existingWorkerLedger
            );
        }

        validateNewSettlementState(settlement, context);
        if (!settlementMapper.findBlockingDisputeIdsForUpdate(
                context.getWorkCaseId()).isEmpty()) {
            throw new InvalidEscrowStateException(
                    "처리 중인 분쟁이 있는 근무 건은 정산할 수 없습니다."
            );
        }
        if (settlementMapper.transitionWaitingToProcessing(
                settlement.getSettlementId(), command.getApproverUserId()) != 1) {
            throw new EscrowIntegrityException(
                    "정산 원장을 처리 중 상태로 전환하지 못했습니다."
            );
        }

        Map<Long, WalletBalanceSnapshot> wallets =
                lockWalletSnapshotsInOrder(context.getEmployerId(), context.getWorkerId());
        WalletBalanceSnapshot employerWallet = wallets.get(context.getEmployerId());
        WalletBalanceSnapshot workerWallet = wallets.get(context.getWorkerId());

        String escrowStatus =
                walletMapper.getEscrowStatusForUpdate(context.getWorkCaseId());
        if (!ESCROW_HELD.equals(escrowStatus)) {
            throw new InvalidEscrowStateException(
                    "정산 가능한 에스크로가 없습니다."
            );
        }

        Long amount = walletMapper.getHeldEscrowAmount(context.getWorkCaseId());
        if (!context.getAgreedWage().equals(amount)
                || !settlement.getAmount().equals(amount)) {
            throw new EscrowIntegrityException(
                    "정산 원장, 에스크로, 약정 임금의 금액이 일치하지 않습니다."
            );
        }
        if (employerWallet.getLockedBalance() < amount) {
            throw new EscrowIntegrityException(
                    "고용주의 잠금 금액이 정산 금액보다 적습니다."
            );
        }

        Long employerLockedAfter = subtractExactly(
                employerWallet.getLockedBalance(),
                amount,
                "정산 후 고용주 잠금 금액이 허용 범위를 벗어납니다."
        );
        Long workerAvailableAfter = addExactly(
                workerWallet.getAvailableBalance(),
                amount,
                "정산 후 근로자 지갑 금액이 허용 범위를 벗어납니다."
        );

        Long escrowId = requireEscrowId(context.getWorkCaseId());
        validateHeldEscrowOwnership(
                walletMapper.findEscrowHoldTransactionSnapshot(
                        context.getWorkCaseId(), escrowId),
                context,
                escrowId
        );

        releaseFunds(context, amount);

        WalletTransactionParam employerTransaction = WalletTransactionParam.builder()
                .walletId(employerWallet.getWalletId())
                .workCaseId(context.getWorkCaseId())
                .transactionType(TX_ESCROW_RELEASE)
                .amount(amount)
                .availableBefore(employerWallet.getAvailableBalance())
                .availableAfter(employerWallet.getAvailableBalance())
                .lockedBefore(employerWallet.getLockedBalance())
                .lockedAfter(employerLockedAfter)
                .referenceType(REF_ESCROW)
                .referenceId(escrowId)
                .idempotencyKey(employerLedgerKey)
                .build();
        insertWalletTransaction(
                employerTransaction,
                "고용주 정산 원장을 기록하지 못했습니다."
        );

        WalletTransactionParam workerTransaction = WalletTransactionParam.builder()
                .walletId(workerWallet.getWalletId())
                .workCaseId(context.getWorkCaseId())
                .transactionType(TX_ESCROW_RELEASE)
                .amount(amount)
                .availableBefore(workerWallet.getAvailableBalance())
                .availableAfter(workerAvailableAfter)
                .lockedBefore(workerWallet.getLockedBalance())
                .lockedAfter(workerWallet.getLockedBalance())
                .referenceType(REF_ESCROW)
                .referenceId(escrowId)
                .idempotencyKey(workerLedgerKey)
                .build();
        insertWalletTransaction(
                workerTransaction,
                "근로자 정산 원장을 기록하지 못했습니다."
        );

        if (settlementMapper.transitionProcessingToCompleted(
                settlement.getSettlementId(), command.getApproverUserId()) != 1) {
            throw new EscrowIntegrityException(
                    "정산 원장을 완료 상태로 전환하지 못했습니다."
            );
        }

        SettlementSnapshot completed =
                settlementMapper.findByWorkCaseIdForUpdate(context.getWorkCaseId());
        validateCompletedSettlement(completed, context);
        return toResult(completed, false);
    }

    private WalletTransactionSnapshot findReleaseLedger(
            String idempotencyKey,
            boolean currentRead) {
        if (currentRead) {
            return settlementLedgerMapper.findByIdempotencyKeyForShare(
                    idempotencyKey);
        }
        return walletMapper.findTransactionByIdempotencyKey(idempotencyKey);
    }

    private SettlementResult replay(
            SettlementSnapshot settlement,
            WorkCaseEscrowSnapshot context,
            WalletTransactionSnapshot employerLedger,
            WalletTransactionSnapshot workerLedger) {
        if (employerLedger == null || workerLedger == null) {
            throw new EscrowIntegrityException(
                    "정산 원장 쌍이 완전하지 않습니다."
            );
        }
        validateReleaseLedgerPair(
                employerLedger,
                workerLedger,
                context
        );
        validateCompletedSettlement(settlement, context);
        if (context.getStatus() != WorkCaseStatus.COMPLETED) {
            throw new EscrowIntegrityException(
                    "완료된 정산과 근무 건 상태가 일치하지 않습니다."
            );
        }

        String escrowStatus =
                walletMapper.getEscrowStatusForUpdate(context.getWorkCaseId());
        if (!ESCROW_RELEASED.equals(escrowStatus)) {
            throw new EscrowIntegrityException(
                    "완료된 정산과 에스크로 상태가 일치하지 않습니다."
            );
        }
        return toResult(settlement, true);
    }

    private void releaseFunds(WorkCaseEscrowSnapshot context, Long amount) {
        if (walletMapper.releaseEscrow(context.getWorkCaseId()) != 1) {
            throw new EscrowIntegrityException(
                    "에스크로 지급 상태를 반영하지 못했습니다."
            );
        }
        if (walletMapper.releaseLockedFunds(context.getEmployerId(), amount) != 1) {
            throw new EscrowIntegrityException(
                    "고용주 잠금 금액을 차감하지 못했습니다."
            );
        }
        if (walletMapper.addAvailableBalance(context.getWorkerId(), amount) != 1) {
            throw new EscrowIntegrityException(
                    "근로자 지갑에 정산금을 반영하지 못했습니다."
            );
        }
        if (context.getStatus() != WorkCaseStatus.COMPLETED) {
            WorkCaseDecision decision = WorkCasePolicy.decideTransition(
                    context.getStatus(), WorkCaseStatus.COMPLETED);
            if (decision != WorkCaseDecision.ALLOWED
                    || workMapper.updateWorkStatus(
                            context.getWorkCaseId(),
                            List.of(context.getStatus()),
                            WorkCaseStatus.COMPLETED) != 1) {
                throw new EscrowIntegrityException(
                        "근무 건 완료 상태를 반영하지 못했습니다."
                );
            }
        }
    }

    private void validateCommand(SettlementApproveCommand command) {
        if (command == null
                || command.getWorkCaseId() == null
                || command.getWorkCaseId() <= 0
                || command.getApproverUserId() == null
                || command.getApproverUserId() <= 0) {
            throw new InvalidEscrowStateException(
                    "정산 승인 요청 정보를 확인해 주세요."
            );
        }
    }

    private void validateContext(
            WorkCaseEscrowSnapshot context,
            SettlementApproveCommand command) {
        if (context == null
                || context.getWorkCaseId() == null
                || !context.getWorkCaseId().equals(command.getWorkCaseId())
                || context.getEmployerId() == null
                || context.getEmployerId() <= 0
                || context.getWorkerId() == null
                || context.getWorkerId() <= 0
                || context.getAgreedWage() == null
                || context.getAgreedWage() <= 0) {
            throw new InvalidEscrowStateException(
                    "유효한 근무 건 계약 정보를 찾을 수 없습니다."
            );
        }
        if (!context.getEmployerId().equals(command.getApproverUserId())) {
            throw new EscrowAccessDeniedException(
                    "근무 건을 정산할 권한이 없습니다."
            );
        }
        if (context.getEmployerId().equals(context.getWorkerId())) {
            throw new InvalidEscrowStateException(
                    "고용주와 근로자가 동일한 근무 건은 정산할 수 없습니다."
            );
        }
    }

    private void validateSettlementIdentity(
            SettlementSnapshot settlement,
            WorkCaseEscrowSnapshot context) {
        if (settlement == null) {
            throw new EscrowIntegrityException(
                    "근무 건의 정산 원장을 찾을 수 없습니다."
            );
        }
        if (settlement.getSettlementId() == null
                || settlement.getSettlementId() <= 0
                || settlement.getWorkCaseId() == null
                || settlement.getAmount() == null
                || settlement.getAmount() <= 0
                || settlement.getStatus() == null) {
            throw new EscrowIntegrityException(
                    "조회된 정산 원장이 올바르지 않습니다."
            );
        }
        if (!context.getWorkCaseId().equals(settlement.getWorkCaseId())
                || !context.getAgreedWage().equals(settlement.getAmount())) {
            throw new EscrowIntegrityException(
                    "정산 원장과 근무 건의 식별자 또는 금액이 일치하지 않습니다."
            );
        }
    }

    private void validateNewSettlementState(
            SettlementSnapshot settlement,
            WorkCaseEscrowSnapshot context) {
        if (SettlementStatus.COMPLETED == settlement.getStatus()) {
            throw new InvalidEscrowStateException(
                    "이미 다른 요청으로 완료된 정산입니다."
            );
        }
        if (SettlementStatus.PROCESSING == settlement.getStatus()) {
            throw new EscrowIntegrityException(
                    "처리 중 상태로 남아 있는 정산 원장은 자동 재개할 수 없습니다."
            );
        }
        if (SettlementStatus.WAITING != settlement.getStatus()) {
            throw new InvalidEscrowStateException(
                    "수동 승인할 수 없는 정산 상태입니다."
            );
        }
        if (settlement.getApprovedByUserId() != null
                || settlement.getProcessingAt() != null
                || settlement.getCompletedAt() != null
                || settlement.getFailureCode() != null) {
            throw new EscrowIntegrityException(
                    "대기 중 정산 원장의 상태 스냅샷이 올바르지 않습니다."
            );
        }
        if (context.getStatus() != WorkCaseStatus.COMPLETED
                && WorkCasePolicy.decideTransition(
                        context.getStatus(),
                        WorkCaseStatus.COMPLETED) != WorkCaseDecision.ALLOWED) {
            throw new InvalidEscrowStateException(
                    "정산할 수 없는 근무 건 상태입니다."
            );
        }
    }

    private void validateCompletedSettlement(
            SettlementSnapshot settlement,
            WorkCaseEscrowSnapshot context) {
        validateSettlementIdentity(settlement, context);
        if (SettlementStatus.COMPLETED != settlement.getStatus()
                || !context.getEmployerId().equals(settlement.getApprovedByUserId())
                || settlement.getProcessingAt() == null
                || settlement.getCompletedAt() == null
                || settlement.getFailureCode() != null) {
            throw new EscrowIntegrityException(
                    "완료된 정산 원장 스냅샷이 올바르지 않습니다."
            );
        }
    }

    private void validateReleaseLedgerPair(
            WalletTransactionSnapshot employerLedger,
            WalletTransactionSnapshot workerLedger,
            WorkCaseEscrowSnapshot context) {
        validateReplayLedger(
                employerLedger,
                context.getEmployerId(),
                context
        );
        validateReplayLedger(
                workerLedger,
                context.getWorkerId(),
                context
        );
        if (!employerLedger.getReferenceId().equals(workerLedger.getReferenceId())) {
            throw new EscrowIntegrityException(
                    "정산 원장 쌍의 에스크로 참조가 일치하지 않습니다."
            );
        }

        Long escrowId = requireEscrowId(context.getWorkCaseId());
        validateEscrowReference(employerLedger, escrowId);
        validateEscrowReference(workerLedger, escrowId);
        validateEmployerReleaseLedgerInvariant(
                employerLedger, context.getAgreedWage());
        validateWorkerReleaseLedgerInvariant(
                workerLedger, context.getAgreedWage());
    }

    private void validateReplayLedger(
            WalletTransactionSnapshot snapshot,
            Long expectedWalletUserId,
            WorkCaseEscrowSnapshot context) {
        if (snapshot.getId() == null
                || snapshot.getId() <= 0
                || snapshot.getWalletId() == null
                || snapshot.getWalletId() <= 0
                || !expectedWalletUserId.equals(snapshot.getWalletUserId())
                || !context.getWorkCaseId().equals(snapshot.getWorkCaseId())
                || !context.getAgreedWage().equals(snapshot.getAmount())
                || !TX_ESCROW_RELEASE.equals(snapshot.getTransactionType())
                || !REF_ESCROW.equals(snapshot.getReferenceType())
                || snapshot.getReferenceId() == null
                || snapshot.getReferenceId() <= 0) {
            throw new IdempotencyKeyReusedException(
                    "같은 멱등 키로 다른 정산 요청이 접수되었습니다."
            );
        }
    }

    private Map<Long, WalletBalanceSnapshot> lockWalletSnapshotsInOrder(
            Long employerId,
            Long workerId) {
        long firstUserId = Math.min(employerId, workerId);
        long secondUserId = Math.max(employerId, workerId);

        Map<Long, WalletBalanceSnapshot> snapshots = new HashMap<>();
        WalletBalanceSnapshot first =
                walletMapper.getWalletSnapshotForUpdate(firstUserId);
        validateWalletSnapshot(first, firstUserId);
        snapshots.put(firstUserId, first);

        WalletBalanceSnapshot second =
                walletMapper.getWalletSnapshotForUpdate(secondUserId);
        validateWalletSnapshot(second, secondUserId);
        snapshots.put(secondUserId, second);
        return snapshots;
    }

    private void validateWalletSnapshot(
            WalletBalanceSnapshot snapshot,
            Long expectedUserId) {
        if (snapshot == null) {
            throw new InvalidEscrowStateException(
                    "정산 대상 지갑을 찾을 수 없습니다."
            );
        }
        if (snapshot.getWalletId() == null
                || snapshot.getWalletId() <= 0
                || !expectedUserId.equals(snapshot.getUserId())
                || snapshot.getAvailableBalance() == null
                || snapshot.getAvailableBalance() < 0
                || snapshot.getLockedBalance() == null
                || snapshot.getLockedBalance() < 0) {
            throw new EscrowIntegrityException(
                    "조회된 지갑 잔액 스냅샷이 올바르지 않습니다."
            );
        }
    }

    private void validateHeldEscrowOwnership(
            WalletTransactionSnapshot snapshot,
            WorkCaseEscrowSnapshot context,
            Long escrowId) {
        if (snapshot == null
                || snapshot.getId() == null
                || snapshot.getId() <= 0
                || snapshot.getWalletId() == null
                || snapshot.getWalletId() <= 0
                || !context.getEmployerId().equals(snapshot.getWalletUserId())
                || !context.getWorkCaseId().equals(snapshot.getWorkCaseId())
                || !context.getAgreedWage().equals(snapshot.getAmount())
                || !TX_ESCROW_HOLD.equals(snapshot.getTransactionType())
                || !REF_ESCROW.equals(snapshot.getReferenceType())
                || !escrowId.equals(snapshot.getReferenceId())) {
            throw new EscrowIntegrityException(
                    "예치 원장과 현재 정산 대상의 소유권이 일치하지 않습니다."
            );
        }
        validateHoldLedgerInvariant(snapshot, context.getAgreedWage());
    }

    private void validateHoldLedgerInvariant(
            WalletTransactionSnapshot snapshot,
            Long amount) {
        if (!hasCompleteBalances(snapshot)
                || !matchesSubtract(
                        snapshot.getAvailableBefore(),
                        amount,
                        snapshot.getAvailableAfter())
                || !matchesAdd(
                        snapshot.getLockedBefore(),
                        amount,
                        snapshot.getLockedAfter())) {
            throw new EscrowIntegrityException(
                    "저장된 에스크로 예치 원장 잔액이 올바르지 않습니다."
            );
        }
    }

    private void validateEmployerReleaseLedgerInvariant(
            WalletTransactionSnapshot snapshot,
            Long amount) {
        if (!hasCompleteBalances(snapshot)
                || !snapshot.getAvailableBefore().equals(snapshot.getAvailableAfter())
                || !matchesSubtract(
                        snapshot.getLockedBefore(),
                        amount,
                        snapshot.getLockedAfter())) {
            throw new EscrowIntegrityException(
                    "저장된 고용주 정산 원장 잔액이 올바르지 않습니다."
            );
        }
    }

    private void validateWorkerReleaseLedgerInvariant(
            WalletTransactionSnapshot snapshot,
            Long amount) {
        if (!hasCompleteBalances(snapshot)
                || !matchesAdd(
                        snapshot.getAvailableBefore(),
                        amount,
                        snapshot.getAvailableAfter())
                || !snapshot.getLockedBefore().equals(snapshot.getLockedAfter())) {
            throw new EscrowIntegrityException(
                    "저장된 근로자 정산 원장 잔액이 올바르지 않습니다."
            );
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

    private void validateEscrowReference(
            WalletTransactionSnapshot snapshot,
            Long escrowId) {
        if (!escrowId.equals(snapshot.getReferenceId())) {
            throw new EscrowIntegrityException(
                    "정산 원장이 다른 에스크로를 참조하고 있습니다."
            );
        }
    }

    private Long requireEscrowId(Long workCaseId) {
        Long escrowId = walletMapper.getEscrowIdByWorkCaseId(workCaseId);
        if (escrowId == null || escrowId <= 0) {
            throw new EscrowIntegrityException(
                    "근무 건의 에스크로를 찾을 수 없습니다."
            );
        }
        return escrowId;
    }

    private void insertWalletTransaction(
            WalletTransactionParam transaction,
            String failureMessage) {
        try {
            if (walletMapper.insertWalletTransaction(transaction) != 1) {
                throw new EscrowIntegrityException(failureMessage);
            }
        } catch (DuplicateKeyException duplicate) {
            throw new IdempotencyKeyReusedException(
                    "같은 멱등 키로 다른 정산 요청이 동시에 접수되었습니다."
            );
        }
    }

    private SettlementResult toResult(
            SettlementSnapshot settlement,
            boolean replayed) {
        return SettlementResult.builder()
                .settlementId(settlement.getSettlementId())
                .status(settlement.getStatus().name())
                .completedAt(settlement.getCompletedAt())
                .replayed(replayed)
                .build();
    }

    private Long addExactly(Long left, Long right, String failureMessage) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new EscrowIntegrityException(failureMessage);
        }
    }

    private Long subtractExactly(Long left, Long right, String failureMessage) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new EscrowIntegrityException(failureMessage);
        }
    }

    private boolean matchesAdd(Long before, Long amount, Long after) {
        try {
            return Math.addExact(before, amount) == after;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private boolean matchesSubtract(Long before, Long amount, Long after) {
        try {
            return Math.subtractExact(before, amount) == after;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }
}
