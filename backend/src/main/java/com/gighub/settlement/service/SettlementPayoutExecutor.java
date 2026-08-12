package com.gighub.settlement.service;

import com.gighub.common.exception.ResourceNotFoundException;
import com.gighub.idempotency.IdempotencyClaimService;
import com.gighub.settlement.domain.SettlementStatus;
import com.gighub.settlement.dto.SettlementSnapshot;
import com.gighub.settlement.exception.SettlementAlreadyProcessedException;
import com.gighub.settlement.exception.SettlementNotReadyException;
import com.gighub.settlement.exception.SettlementOnHoldException;
import com.gighub.settlement.mapper.SettlementMapper;
import com.gighub.settlement.service.command.SettlementApproveCommand;
import com.gighub.settlement.service.result.SettlementResult;
import com.gighub.wallet.exception.EscrowIntegrityException;
import com.gighub.wallet.exception.InvalidEscrowStateException;
import com.gighub.wallet.idempotency.WalletIdempotencyKeys;
import com.gighub.wallet.service.SettlementWalletService;
import com.gighub.wallet.service.SettlementWalletService.SettlementAmounts;
import com.gighub.wallet.service.command.SettlementWalletCommand;
import com.gighub.work.contract.WorkCaseEscrowSnapshot;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.service.WorkSettlementService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Work·Settlement·Escrow·Wallet·원장을 하나의 지급 Transaction으로 확정합니다. */
@Service
public class SettlementPayoutExecutor {

    private static final int RESPONSE_HTTP_STATUS = 200;

    private final SettlementMapper settlementMapper;
    private final WorkSettlementService workSettlementService;
    private final SettlementWalletService settlementWalletService;
    private final IdempotencyClaimService claimService;
    private final SettlementReplayCodec replayCodec;

    public SettlementPayoutExecutor(
            SettlementMapper settlementMapper,
            WorkSettlementService workSettlementService,
            SettlementWalletService settlementWalletService,
            IdempotencyClaimService claimService,
            SettlementReplayCodec replayCodec) {
        this.settlementMapper = settlementMapper;
        this.workSettlementService = workSettlementService;
        this.settlementWalletService = settlementWalletService;
        this.claimService = claimService;
        this.replayCodec = replayCodec;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SettlementResult execute(SettlementApproveCommand command, long claimId) {
        // 서로 다른 OWNER 요청과 향후 Scheduler가 같은 자원을 반대 순서로 잡지 않도록
        // Work → Settlement → Escrow 순서를 고정합니다. Work는 확인만 하며 변경하지 않습니다.
        WorkCaseEscrowSnapshot context =
                workSettlementService.lockEscrowContext(command.getWorkCaseId());
        validateContext(context, command);

        SettlementSnapshot settlement =
                settlementMapper.findByWorkCaseIdForUpdate(command.getWorkCaseId());
        validateSettlementIdentity(settlement, context);
        validateNewSettlementState(settlement);

        SettlementWalletCommand walletCommand = SettlementWalletCommand.builder()
                .workCaseId(context.getWorkCaseId())
                .employerId(context.getEmployerId())
                .workerId(context.getWorkerId())
                .amount(context.getAgreedWage())
                .employerLedgerKey(WalletIdempotencyKeys.settlementReleaseOwner(
                        settlement.getSettlementId()))
                .workerLedgerKey(WalletIdempotencyKeys.settlementReleaseWorker(
                        settlement.getSettlementId()))
                .build();

        long escrowId;
        try {
            escrowId = settlementWalletService.lockHeldEscrow(walletCommand);
        } catch (InvalidEscrowStateException notHeld) {
            throw new SettlementNotReadyException();
        }

        if (!settlementMapper.findBlockingDisputeIdsForUpdate(
                context.getWorkCaseId()).isEmpty()) {
            throw new SettlementOnHoldException();
        }
        if (settlementMapper.transitionScheduledToProcessing(
                settlement.getSettlementId(), command.getApproverUserId()) != 1) {
            throw new EscrowIntegrityException("정산 원장을 처리 중 상태로 전환하지 못했습니다.");
        }

        // Escrow 잠금 뒤에 지갑을 user ID 오름차순으로 잠급니다. 양쪽 잔액·Escrow·원장은
        // 이 Transaction 안에서만 바뀌며 뒤 단계 실패 시 모두 함께 Rollback됩니다.
        SettlementAmounts amounts = settlementWalletService.release(walletCommand, escrowId);

        if (settlementMapper.transitionProcessingToCompleted(
                settlement.getSettlementId(), command.getApproverUserId()) != 1) {
            throw new EscrowIntegrityException("정산 원장을 완료 상태로 전환하지 못했습니다.");
        }
        SettlementSnapshot completed =
                settlementMapper.findByWorkCaseIdForUpdate(context.getWorkCaseId());
        validateCompletedSettlement(completed, context);

        SettlementResult result = toResult(completed, amounts);
        // 성공 응답 Snapshot은 지급과 같은 Transaction의 마지막 변경으로 완료합니다.
        // 자금만 Commit되고 Replay 응답이 없는 부분 성공을 허용하지 않습니다.
        claimService.complete(
                claimId, RESPONSE_HTTP_STATUS, replayCodec.writeResponseBody(result));
        return result;
    }

    private void validateContext(
            WorkCaseEscrowSnapshot context, SettlementApproveCommand command) {
        if (context == null
                || context.getWorkCaseId() == null
                || !context.getWorkCaseId().equals(command.getWorkCaseId())) {
            throw new ResourceNotFoundException("근무 건을 찾을 수 없습니다.");
        }
        // 다른 OWNER의 존재 여부를 드러내지 않기 위해 미존재와 같은 404로 처리합니다.
        if (!command.getApproverUserId().equals(context.getEmployerId())) {
            throw new ResourceNotFoundException("근무 건을 찾을 수 없습니다.");
        }
        if (context.getEmployerId() == null
                || context.getEmployerId() <= 0
                || context.getWorkerId() == null
                || context.getWorkerId() <= 0
                || context.getAgreedWage() == null
                || context.getAgreedWage() <= 0
                || context.getEmployerId().equals(context.getWorkerId())) {
            throw new EscrowIntegrityException("근무 건의 정산 계약 정보가 올바르지 않습니다.");
        }
        if (context.getStatus() != WorkCaseStatus.COMPLETED) {
            throw new SettlementNotReadyException();
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

    private void validateNewSettlementState(SettlementSnapshot settlement) {
        if (settlement.getStatus() == SettlementStatus.ON_HOLD) {
            throw new SettlementOnHoldException();
        }
        if (settlement.getStatus() == SettlementStatus.COMPLETED) {
            throw new SettlementAlreadyProcessedException();
        }
        if (settlement.getStatus() != SettlementStatus.SCHEDULED) {
            throw new SettlementNotReadyException();
        }
        if (settlement.getDueAt() == null
                || settlement.getApprovedByUserId() != null
                || settlement.getProcessingAt() != null
                || settlement.getCompletedAt() != null
                || settlement.getFailureCode() != null) {
            throw new EscrowIntegrityException(
                    "지급 예정 정산 원장의 상태 스냅샷이 올바르지 않습니다.");
        }
    }

    private void validateCompletedSettlement(
            SettlementSnapshot settlement, WorkCaseEscrowSnapshot context) {
        validateSettlementIdentity(settlement, context);
        if (settlement.getStatus() != SettlementStatus.COMPLETED
                || !context.getEmployerId().equals(settlement.getApprovedByUserId())
                || settlement.getDueAt() == null
                || settlement.getProcessingAt() == null
                || settlement.getCompletedAt() == null
                || settlement.getFailureCode() != null) {
            throw new EscrowIntegrityException(
                    "완료된 정산 원장 스냅샷이 올바르지 않습니다.");
        }
    }

    private SettlementResult toResult(
            SettlementSnapshot settlement, SettlementAmounts amounts) {
        validateSettlementAmounts(settlement, amounts);
        return SettlementResult.builder()
                .settlementId(settlement.getSettlementId())
                .status(settlement.getStatus().name())
                .settlementAmount(settlement.getAmount())
                .originalEscrowAmount(amounts.originalEscrowAmount())
                .workerPaidAmount(amounts.workerPaidAmount())
                .ownerRefundAmount(amounts.ownerRefundAmount())
                .completedAt(settlement.getCompletedAt())
                .replayed(false)
                .build();
    }

    private void validateSettlementAmounts(
            SettlementSnapshot settlement, SettlementAmounts amounts) {
        if (amounts == null
                || amounts.originalEscrowAmount() < 0
                || amounts.workerPaidAmount() < 0
                || amounts.ownerRefundAmount() < 0
                || settlement.getAmount() != amounts.originalEscrowAmount()
                || !preservesEscrow(amounts)) {
            throw new EscrowIntegrityException(
                    "자금 실행 결과가 정산 원금 보존 계약과 일치하지 않습니다.");
        }
    }

    private boolean preservesEscrow(SettlementAmounts amounts) {
        try {
            return Math.addExact(
                    amounts.workerPaidAmount(),
                    amounts.ownerRefundAmount()) == amounts.originalEscrowAmount();
        } catch (ArithmeticException overflow) {
            return false;
        }
    }
}
