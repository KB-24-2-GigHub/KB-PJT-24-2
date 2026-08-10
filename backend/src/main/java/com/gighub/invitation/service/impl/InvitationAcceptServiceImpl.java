package com.gighub.invitation.service.impl;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.contract.ContractArtifactPort;
import com.gighub.idempotency.IdempotencyClaimResult;
import com.gighub.idempotency.IdempotencyClaimService;
import com.gighub.idempotency.IdempotencyKeys;
import com.gighub.invitation.application.InvitationAcceptanceCommand;
import com.gighub.invitation.application.InvitationAcceptanceOrchestrator;
import com.gighub.invitation.application.InvitationAcceptanceOutcome;
import com.gighub.invitation.application.InvitationAcceptanceReplaySnapshotCodec;
import com.gighub.invitation.application.InvitationAcceptanceResult;
import com.gighub.invitation.exception.InvitationNotFoundException;
import com.gighub.invitation.mapper.InvitationMapper;
import com.gighub.invitation.mapper.result.InvitationRow;
import com.gighub.invitation.service.InvitationAcceptResult;
import com.gighub.invitation.service.InvitationAcceptService;
import com.gighub.invitation.token.InvitationTokenCodec;
import com.gighub.member.domain.UserRole;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 멱등 Claim 생명주기를 감싸고 본 처리를 Transaction 실행기에 넘깁니다.
 *
 * <p>이 클래스에는 {@code @Transactional}이 없습니다. Claim 선점과 포기는 본 처리와 다른
 * Transaction이어야 하는데, 한 Method를 하나의 Transaction으로 묶으면 그 경계가 사라집니다.
 * 특히 포기는 본 처리가 <b>끝난 뒤</b>에 실행돼야 합니다. 본 처리가 Claim 행을 잠근 채
 * 새 Transaction으로 같은 행을 지우려 하면 스스로 교착합니다.</p>
 */
@Service
public class InvitationAcceptServiceImpl implements InvitationAcceptService {

    /** 멱등 저장 범위를 나누는 Operation 표지입니다. */
    private static final String OPERATION_CODE = "INVITATION_ACCEPT";
    private static final int MAX_TRANSACTION_ATTEMPTS = 3;

    private final InvitationMapper invitationMapper;
    private final InvitationTokenCodec tokenCodec;
    private final IdempotencyClaimService claimService;
    private final InvitationAcceptanceOrchestrator orchestrator;
    private final InvitationAcceptanceReplaySnapshotCodec replaySnapshotCodec;
    private final ContractArtifactPort contractArtifactPort;

    public InvitationAcceptServiceImpl(
            InvitationMapper invitationMapper,
            InvitationTokenCodec tokenCodec,
            IdempotencyClaimService claimService,
            InvitationAcceptanceOrchestrator orchestrator,
            InvitationAcceptanceReplaySnapshotCodec replaySnapshotCodec,
            ContractArtifactPort contractArtifactPort) {
        this.invitationMapper = invitationMapper;
        this.tokenCodec = tokenCodec;
        this.claimService = claimService;
        this.orchestrator = orchestrator;
        this.replaySnapshotCodec = replaySnapshotCodec;
        this.contractArtifactPort = contractArtifactPort;
    }

    @Override
    public InvitationAcceptResult accept(
            AuthPrincipal principal,
            String token,
            String rawKey) {
        if (principal.getRole() != UserRole.WORKER) {
            throw new RoleMismatchException("초대는 WORKER만 수락할 수 있습니다.");
        }
        // Key 형식을 Token 조회보다 먼저 봅니다. 승인 순서가 "Key 형식 -> Token 존재"라,
        // 순서를 바꾸면 Key도 Token도 잘못된 요청이 400 대신 404로 끝납니다.
        IdempotencyKeys.validate(rawKey);

        // 형식 오류·미존재는 Claim을 만들지 않고 끝냅니다. 저장하면 같은 Key로 올바른
        // Token을 다시 시도할 수 없습니다.
        if (!tokenCodec.isWellFormed(token)) {
            throw new InvitationNotFoundException();
        }

        byte[] tokenHash = tokenCodec.hash(token);
        InvitationRow invitation = invitationMapper.findByTokenHash(tokenHash);
        if (invitation == null) {
            throw new InvitationNotFoundException();
        }

        IdempotencyClaimResult claim = claimService.claim(
                principal.getUserId(),
                OPERATION_CODE,
                rawKey,
                fingerprint(tokenHash, invitation.getExpectedTermsVersion()));
        if (claim.isReplay()) {
            return InvitationAcceptResult.replayed(
                    replaySnapshotCodec.readResponseBody(claim.getResponseBody()));
        }

        InvitationAcceptanceCommand command = InvitationAcceptanceCommand.of(
                principal.getUserId(),
                invitation.getId(),
                invitation.getWorkCaseId(),
                tokenHash,
                claim.getClaimId());
        return InvitationAcceptResult.first(runAggregate(command));
    }

    /**
     * 본 처리를 실행하고 Transaction이 끝난 뒤의 뒷정리를 순서대로 수행합니다.
     *
     * <p>성공하면 임시 저장된 계약서 파일을 최종 위치로 승격시킵니다. Commit이 끝난 뒤라
     * 승격이 실패해도 수락을 되돌리지 않습니다.</p>
     *
     * <p>잠금 실패는 남은 임시 파일만 정리하고 같은 Claim으로 새 Transaction 전체를 최대
     * 세 번 실행합니다. 중간 시도에서 Claim을 지우면 같은 요청의 재시도 단위가 깨집니다.
     * 비재시도 실패나 최종 소진 때만 Claim을 지우며, 성공 Claim은 본 처리 Transaction 안에서
     * 이미 완료 상태로 Commit됐으므로 손대지 않습니다.</p>
     */
    private InvitationAcceptanceResult runAggregate(InvitationAcceptanceCommand command) {
        for (int attempt = 1; attempt <= MAX_TRANSACTION_ATTEMPTS; attempt++) {
            InvitationAcceptanceOutcome outcome;
            try {
                outcome = orchestrator.execute(command);
            } catch (PessimisticLockingFailureException transientFailure) {
                // 이 시도의 DB 변경은 새 outer Transaction과 함께 전부 Rollback됐습니다.
                // 임시 파일만 정리하고 동일 Claim으로 명령 전체를 다시 실행합니다.
                contractArtifactPort.discardPending(command.getWorkCaseId());
                if (attempt == MAX_TRANSACTION_ATTEMPTS) {
                    claimService.abandon(command.getClaimId());
                    throw transientFailure;
                }
                continue;
            } catch (RuntimeException failure) {
                contractArtifactPort.discardPending(command.getWorkCaseId());
                claimService.abandon(command.getClaimId());
                throw failure;
            }
            // promote는 commit 뒤의 best-effort 경계입니다. 이 호출의 실패를 Aggregate 실패로
            // 분류하거나 이미 COMPLETED인 Claim을 abandon해서는 안 됩니다.
            contractArtifactPort.promote(outcome.getArtifact());
            return outcome.getResult();
        }
        throw new IllegalStateException("초대 수락 재시도 횟수 계산이 올바르지 않습니다.");
    }

    /**
     * 같은 요청인지 판정할 Fingerprint를 만듭니다.
     *
     * <p>승인 규칙은 Token Hash 소문자 Hex와 초대가 기대한 조건 Version입니다. Token 원문과
     * Header Key는 입력에 넣지 않으므로 Fingerprint를 저장해도 둘을 복원할 수 없습니다. 같은
     * Key로 다른 Token이나 다른 조건 Version을 보내면 값이 달라져 재사용으로 거절됩니다.</p>
     */
    private static byte[] fingerprint(byte[] tokenHash, int expectedTermsVersion) {
        String source = OPERATION_CODE + "\n"
                + HexFormat.of().formatHex(tokenHash) + "\n"
                + expectedTermsVersion;
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
