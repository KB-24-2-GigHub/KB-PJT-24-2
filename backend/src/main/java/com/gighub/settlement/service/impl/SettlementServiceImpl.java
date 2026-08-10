package com.gighub.settlement.service.impl;

import com.gighub.settlement.domain.SettlementStatus;
import com.gighub.settlement.dto.SettlementSnapshot;
import com.gighub.settlement.mapper.SettlementMapper;
import com.gighub.settlement.service.SettlementService;
import com.gighub.settlement.service.command.SettlementApproveCommand;
import com.gighub.settlement.service.result.SettlementResult;
import com.gighub.wallet.exception.EscrowAccessDeniedException;
import com.gighub.wallet.exception.EscrowIntegrityException;
import com.gighub.wallet.exception.InvalidEscrowStateException;
import com.gighub.wallet.idempotency.WalletIdempotencyKeys;
import com.gighub.wallet.service.SettlementWalletService;
import com.gighub.wallet.service.command.SettlementWalletCommand;
import com.gighub.work.contract.WorkCaseEscrowSnapshot;
import com.gighub.work.domain.WorkCaseDecision;
import com.gighub.work.domain.WorkCasePolicy;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.service.WorkSettlementService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Settlement payout outer Transaction과 participant 호출 순서를 소유합니다. */
@Service
public class SettlementServiceImpl implements SettlementService {

    private final SettlementMapper settlementMapper;
    private final WorkSettlementService workSettlementService;
    private final SettlementWalletService settlementWalletService;

    public SettlementServiceImpl(
            SettlementMapper settlementMapper,
            WorkSettlementService workSettlementService,
            SettlementWalletService settlementWalletService) {
        this.settlementMapper = settlementMapper;
        this.workSettlementService = workSettlementService;
        this.settlementWalletService = settlementWalletService;
    }

    @Override
    @Transactional
    public SettlementResult approve(SettlementApproveCommand command) {
        validateCommand(command);

        WorkCaseEscrowSnapshot context = workSettlementService.lockEscrowContext(
                command.getWorkCaseId());
        validateContext(context, command);
        SettlementSnapshot settlement = settlementMapper.findByWorkCaseIdForUpdate(
                command.getWorkCaseId());
        validateSettlementIdentity(settlement, context);

        SettlementWalletCommand walletCommand = SettlementWalletCommand.builder()
                .workCaseId(context.getWorkCaseId())
                .employerId(context.getEmployerId())
                .workerId(context.getWorkerId())
                .amount(context.getAgreedWage())
                .employerLedgerKey(WalletIdempotencyKeys.escrowReleaseEmployer(
                        command.getIdempotencyKey()))
                .workerLedgerKey(WalletIdempotencyKeys.escrowReleaseWorker(
                        command.getIdempotencyKey()))
                .build();

        if (settlementWalletService.verifyReplay(walletCommand)) {
            validateCompletedSettlement(settlement, context);
            if (context.getStatus() != WorkCaseStatus.COMPLETED) {
                throw new EscrowIntegrityException(
                        "완료된 정산과 근무 건 상태가 일치하지 않습니다.");
            }
            return toResult(settlement, true);
        }

        validateNewSettlementState(settlement, context);
        if (!settlementMapper.findBlockingDisputeIdsForUpdate(
                context.getWorkCaseId()).isEmpty()) {
            throw new InvalidEscrowStateException(
                    "처리 중인 분쟁이 있는 근무 건은 정산할 수 없습니다.");
        }
        if (settlementMapper.transitionWaitingToProcessing(
                settlement.getSettlementId(), command.getApproverUserId()) != 1) {
            throw new EscrowIntegrityException("정산 원장을 처리 중 상태로 전환하지 못했습니다.");
        }

        settlementWalletService.release(walletCommand);
        if (!workSettlementService.completeForPayout(context)) {
            throw new EscrowIntegrityException("근무 건 완료 상태를 반영하지 못했습니다.");
        }

        if (settlementMapper.transitionProcessingToCompleted(
                settlement.getSettlementId(), command.getApproverUserId()) != 1) {
            throw new EscrowIntegrityException("정산 원장을 완료 상태로 전환하지 못했습니다.");
        }
        SettlementSnapshot completed = settlementMapper.findByWorkCaseIdForUpdate(
                context.getWorkCaseId());
        validateCompletedSettlement(completed, context);
        return toResult(completed, false);
    }

    private void validateCommand(SettlementApproveCommand command) {
        if (command == null
                || command.getWorkCaseId() == null
                || command.getWorkCaseId() <= 0
                || command.getApproverUserId() == null
                || command.getApproverUserId() <= 0) {
            throw new InvalidEscrowStateException("정산 승인 요청 정보를 확인해 주세요.");
        }
    }

    private void validateContext(
            WorkCaseEscrowSnapshot context, SettlementApproveCommand command) {
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
                    "유효한 근무 건 계약 정보를 찾을 수 없습니다.");
        }
        if (!context.getEmployerId().equals(command.getApproverUserId())) {
            throw new EscrowAccessDeniedException("근무 건을 정산할 권한이 없습니다.");
        }
        if (context.getEmployerId().equals(context.getWorkerId())) {
            throw new InvalidEscrowStateException(
                    "고용주와 근로자가 동일한 근무 건은 정산할 수 없습니다.");
        }
    }

    private void validateSettlementIdentity(
            SettlementSnapshot settlement, WorkCaseEscrowSnapshot context) {
        if (settlement == null) {
            throw new EscrowIntegrityException("근무 건의 정산 원장을 찾을 수 없습니다.");
        }
        if (settlement.getSettlementId() == null
                || settlement.getSettlementId() <= 0
                || settlement.getWorkCaseId() == null
                || settlement.getAmount() == null
                || settlement.getAmount() <= 0
                || settlement.getStatus() == null) {
            throw new EscrowIntegrityException("조회된 정산 원장이 올바르지 않습니다.");
        }
        if (!context.getWorkCaseId().equals(settlement.getWorkCaseId())
                || !context.getAgreedWage().equals(settlement.getAmount())) {
            throw new EscrowIntegrityException(
                    "정산 원장과 근무 건의 식별자 또는 금액이 일치하지 않습니다.");
        }
    }

    private void validateNewSettlementState(
            SettlementSnapshot settlement, WorkCaseEscrowSnapshot context) {
        if (SettlementStatus.COMPLETED == settlement.getStatus()) {
            throw new InvalidEscrowStateException("이미 다른 요청으로 완료된 정산입니다.");
        }
        if (SettlementStatus.PROCESSING == settlement.getStatus()) {
            throw new EscrowIntegrityException(
                    "처리 중 상태로 남아 있는 정산 원장은 자동 재개할 수 없습니다.");
        }
        if (SettlementStatus.WAITING != settlement.getStatus()) {
            throw new InvalidEscrowStateException("수동 승인할 수 없는 정산 상태입니다.");
        }
        if (settlement.getApprovedByUserId() != null
                || settlement.getProcessingAt() != null
                || settlement.getCompletedAt() != null
                || settlement.getFailureCode() != null) {
            throw new EscrowIntegrityException(
                    "대기 중 정산 원장의 상태 스냅샷이 올바르지 않습니다.");
        }
        if (context.getStatus() != WorkCaseStatus.COMPLETED
                && WorkCasePolicy.decideTransition(
                        context.getStatus(), WorkCaseStatus.COMPLETED)
                != WorkCaseDecision.ALLOWED) {
            throw new InvalidEscrowStateException("정산할 수 없는 근무 건 상태입니다.");
        }
    }

    private void validateCompletedSettlement(
            SettlementSnapshot settlement, WorkCaseEscrowSnapshot context) {
        validateSettlementIdentity(settlement, context);
        if (SettlementStatus.COMPLETED != settlement.getStatus()
                || !context.getEmployerId().equals(settlement.getApprovedByUserId())
                || settlement.getProcessingAt() == null
                || settlement.getCompletedAt() == null
                || settlement.getFailureCode() != null) {
            throw new EscrowIntegrityException(
                    "완료된 정산 원장 스냅샷이 올바르지 않습니다.");
        }
    }

    private SettlementResult toResult(SettlementSnapshot settlement, boolean replayed) {
        return SettlementResult.builder()
                .settlementId(settlement.getSettlementId())
                .status(settlement.getStatus().name())
                .completedAt(settlement.getCompletedAt())
                .replayed(replayed)
                .build();
    }
}
