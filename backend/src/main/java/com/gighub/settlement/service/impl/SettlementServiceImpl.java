package com.gighub.settlement.service.impl;

import com.gighub.common.exception.RoleMismatchException;
import com.gighub.idempotency.IdempotencyClaimResult;
import com.gighub.idempotency.IdempotencyClaimService;
import com.gighub.idempotency.IdempotencyKeys;
import com.gighub.member.domain.UserRole;
import com.gighub.settlement.exception.SettlementTemporarilyUnavailableException;
import com.gighub.settlement.service.SettlementApprovalTransaction;
import com.gighub.settlement.service.SettlementReplayCodec;
import com.gighub.settlement.service.SettlementService;
import com.gighub.settlement.service.command.SettlementApproveCommand;
import com.gighub.settlement.service.result.SettlementResult;
import com.gighub.wallet.exception.InvalidEscrowStateException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** OWNER 정산 승인의 외부 멱등 Claim 생명주기를 관리합니다. */
@Service
@RequiredArgsConstructor
public class SettlementServiceImpl implements SettlementService {

    private static final String OPERATION_CODE = "SETTLEMENT_APPROVE";
    private static final int MAX_TRANSACTION_ATTEMPTS = 3;

    private final IdempotencyClaimService claimService;
    private final SettlementApprovalTransaction approvalTransaction;
    private final SettlementReplayCodec replayCodec;

    @Override
    public SettlementResult approve(SettlementApproveCommand command) {
        validateCommand(command);
        String rawKey = IdempotencyKeys.validate(command.getIdempotencyKey());

        IdempotencyClaimResult claim = claimService.claim(
                command.getApproverUserId(),
                OPERATION_CODE,
                rawKey,
                fingerprint(command.getWorkCaseId()));
        if (claim.isReplay()) {
            if (claim.getResponseHttpStatus() != 200) {
                throw new IllegalStateException("저장된 정산 승인 응답 상태가 올바르지 않습니다.");
            }
            return replayCodec.readResponseBody(claim.getResponseBody());
        }

        return executeClaimed(command, claim.getClaimId());
    }

    /**
     * 한 Claim으로 지급 Transaction 전체를 제한된 횟수만 다시 실행합니다.
     *
     * <p>각 시도는 {@link SettlementApprovalTransaction}이 소유한 새 Transaction입니다.
     * 실패 Transaction이 완전히
     * Rollback된 뒤에만 다음 시도를 시작하고, 최종 실패 때 Claim을 지워 같은 외부 Key로
     * 안전하게 재요청할 수 있게 합니다.</p>
     */
    private SettlementResult executeClaimed(
            SettlementApproveCommand command, long claimId) {
        for (int attempt = 1; attempt <= MAX_TRANSACTION_ATTEMPTS; attempt++) {
            try {
                return approvalTransaction.execute(command, claimId);
            } catch (PessimisticLockingFailureException transientFailure) {
                if (attempt == MAX_TRANSACTION_ATTEMPTS) {
                    claimService.abandon(claimId);
                    throw new SettlementTemporarilyUnavailableException();
                }
            } catch (RuntimeException failure) {
                claimService.abandon(claimId);
                throw failure;
            }
        }
        throw new IllegalStateException("정산 지급 재시도 횟수 계산이 올바르지 않습니다.");
    }

    private void validateCommand(SettlementApproveCommand command) {
        if (command == null
                || command.getWorkCaseId() == null
                || command.getWorkCaseId() <= 0
                || command.getApproverUserId() == null
                || command.getApproverUserId() <= 0) {
            throw new InvalidEscrowStateException("정산 승인 요청 정보를 확인해 주세요.");
        }
        if (command.getApproverRole() != UserRole.OWNER) {
            throw new RoleMismatchException("정산 승인은 OWNER만 사용할 수 있습니다.");
        }
    }

    /** Body가 없는 Endpoint이므로 Work Case ID만 같은 의도 판정에 포함합니다. */
    private static byte[] fingerprint(long workCaseId) {
        String source = OPERATION_CODE + "\n" + workCaseId;
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
