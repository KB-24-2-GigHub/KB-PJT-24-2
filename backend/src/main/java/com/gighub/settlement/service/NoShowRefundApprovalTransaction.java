package com.gighub.settlement.service;

import com.gighub.common.exception.ResourceNotFoundException;
import com.gighub.idempotency.IdempotencyClaimService;
import com.gighub.settlement.exception.SettlementAlreadyProcessedException;
import com.gighub.settlement.exception.SettlementNotReadyException;
import com.gighub.settlement.exception.SettlementOnHoldException;
import com.gighub.settlement.service.command.NoShowRefundApproveCommand;
import com.gighub.settlement.service.policy.SettlementPayoutDecision;
import com.gighub.settlement.service.policy.SettlementPayoutRejectedException;
import com.gighub.settlement.service.result.SettlementResult;
import com.gighub.wallet.exception.EscrowIntegrityException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** OWNER NO_SHOW 환불과 성공 Claim 완료를 하나의 Transaction으로 묶습니다. */
@Service
@RequiredArgsConstructor
public class NoShowRefundApprovalTransaction {

    private static final int RESPONSE_HTTP_STATUS = 200;

    private final NoShowRefundExecutor refundExecutor;
    private final IdempotencyClaimService claimService;
    private final SettlementReplayCodec replayCodec;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SettlementResult execute(NoShowRefundApproveCommand command, long claimId) {
        SettlementResult result;
        try {
            result = refundExecutor.execute(
                    command.getWorkCaseId(), command.getApproverUserId());
        } catch (SettlementPayoutRejectedException rejected) {
            throw translateOwnerRejection(rejected.getDecision());
        }
        // Claim 완료 실패 시 환불도 함께 Rollback하여 부분 성공을 남기지 않습니다.
        claimService.complete(
                claimId, RESPONSE_HTTP_STATUS, replayCodec.writeResponseBody(result));
        return result;
    }

    private RuntimeException translateOwnerRejection(SettlementPayoutDecision decision) {
        return switch (decision) {
            case RESOURCE_NOT_FOUND -> new ResourceNotFoundException("근무 건을 찾을 수 없습니다.");
            case NOT_READY -> new SettlementNotReadyException();
            case ON_HOLD -> new SettlementOnHoldException();
            case ALREADY_PROCESSED -> new SettlementAlreadyProcessedException();
            case INTEGRITY_VIOLATION ->
                    new EscrowIntegrityException("NO_SHOW 환불 Snapshot의 무결성이 올바르지 않습니다.");
            case ALLOWED -> new IllegalStateException("허용된 환불 결과를 거절로 변환할 수 없습니다.");
        };
    }
}
