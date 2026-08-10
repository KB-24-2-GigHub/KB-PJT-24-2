package com.gighub.invitation.service.impl;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.api.ApiTimes;
import com.gighub.common.exception.ForbiddenException;
import com.gighub.common.exception.ConflictException;
import com.gighub.common.exception.WorkCaseLockedException;
import com.gighub.contract.ContractArtifactCommand;
import com.gighub.contract.ContractArtifactHandle;
import com.gighub.contract.ContractArtifactPort;
import com.gighub.contract.domain.AcceptedContract;
import com.gighub.contract.domain.ContractTermsSnapshot;
import com.gighub.contract.mapper.WorkContractMapper;
import com.gighub.contract.mapper.param.WorkContractInsertParam;
import com.gighub.contract.mapper.result.ContractPartyNamesRow;
import com.gighub.idempotency.IdempotencyClaimService;
import com.gighub.invitation.domain.InvitationDecision;
import com.gighub.invitation.domain.InvitationPolicy;
import com.gighub.invitation.dto.InvitationAcceptResponse;
import com.gighub.invitation.exception.InvitationAlreadyAcceptedException;
import com.gighub.invitation.exception.InvitationExpiredException;
import com.gighub.invitation.exception.InvitationNotFoundException;
import com.gighub.invitation.exception.InvitationRevokedException;
import com.gighub.invitation.exception.InvitationTermsChangedException;
import com.gighub.invitation.mapper.InvitationMapper;
import com.gighub.invitation.mapper.result.AcceptWorkCaseLockRow;
import com.gighub.invitation.mapper.result.InvitationRow;
import com.gighub.invitation.service.AcceptEscrowHold;
import com.gighub.settlement.mapper.SettlementMapper;
import com.gighub.work.domain.WorkCaseDecision;
import com.gighub.work.domain.WorkCasePolicy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 수락의 모든 DB 변경을 하나의 Transaction으로 확정합니다.
 *
 * <p>잠금 순서는 <b>Claim → 근무 → 초대 → 지갑</b>입니다. 조건 수정(#154)과 초대 발급도 근무
 * 행을 먼저 잠그므로 세 흐름이 동시에 실행돼도 교착이 생기지 않습니다.</p>
 *
 * <p>Claim 행은 별도로 잠그지 않고 마지막 완료 {@code UPDATE}에서 잠깁니다. 같은 Key로는 한
 * 요청만 선점에 성공해 본 처리에 들어오므로 그 행에는 경합이 없고, Claim을 먼저 잡는 다른
 * 흐름도 없어 순서가 엇갈릴 여지가 없습니다.</p>
 *
 * <p>잠금 전에 읽은 값은 판정에 쓰지 않습니다. 잠근 뒤 상태·조건 Version·매칭을 처음부터 다시
 * 확인하고, 상태 전이는 {@code WHERE} 조건으로 다시 한번 방어합니다.</p>
 *
 * <p>만료 전이는 {@code 410}과 함께 보존해야 합니다. 만료된 초대가 {@code PENDING}으로 남으면
 * 활성 초대 Unique 제약이 OWNER의 새 발급을 계속 막기 때문에 이 예외에서는 Rollback하지
 * 않습니다. 다른 실패는 모두 Rollback하며 Claim 삭제는 호출부가 Transaction 밖에서 합니다.</p>
 */
@Component
public class AcceptAggregateExecutor {

    private static final String NOT_ACCEPTABLE = "확정할 수 없는 근무입니다.";
    private static final String UNUSABLE_INVITATION = "초대 상태를 다시 확인해 주세요.";

    /** DB의 DATETIME은 Asia/Seoul 벽시계 값이므로 비교 기준 시각도 같은 지역으로 만듭니다. */
    private static final java.time.ZoneId DATABASE_ZONE = java.time.ZoneId.of("Asia/Seoul");

    private final InvitationMapper invitationMapper;
    private final WorkContractMapper workContractMapper;
    private final SettlementMapper settlementMapper;
    private final AcceptEscrowHold escrowHold;
    private final IdempotencyClaimService claimService;
    private final AcceptJson acceptJson;
    private final ContractArtifactPort contractArtifactPort;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public AcceptAggregateExecutor(
            InvitationMapper invitationMapper,
            WorkContractMapper workContractMapper,
            SettlementMapper settlementMapper,
            AcceptEscrowHold escrowHold,
            IdempotencyClaimService claimService,
            AcceptJson acceptJson,
            ContractArtifactPort contractArtifactPort) {
        this(
                invitationMapper,
                workContractMapper,
                settlementMapper,
                escrowHold,
                claimService,
                acceptJson,
                contractArtifactPort,
                Clock.system(DATABASE_ZONE));
    }

    /** 만료·시작 시각 경계를 검증할 때만 고정 Clock을 주입합니다. */
    AcceptAggregateExecutor(
            InvitationMapper invitationMapper,
            WorkContractMapper workContractMapper,
            SettlementMapper settlementMapper,
            AcceptEscrowHold escrowHold,
            IdempotencyClaimService claimService,
            AcceptJson acceptJson,
            ContractArtifactPort contractArtifactPort,
            Clock clock) {
        this.invitationMapper = invitationMapper;
        this.workContractMapper = workContractMapper;
        this.settlementMapper = settlementMapper;
        this.escrowHold = escrowHold;
        this.claimService = claimService;
        this.acceptJson = acceptJson;
        this.contractArtifactPort = contractArtifactPort;
        this.clock = clock;
    }

    /**
     * @param principal    인증 WORKER
     * @param invitationId 잠금 전 조회로 찾은 초대 식별자
     * @param workCaseId   그 초대가 가리키는 근무 식별자
     * @param tokenHash    요청 Token의 Hash. 잠근 행과 다시 대조합니다
     * @param claimId      선점한 멱등 Claim 식별자
     */
    @Transactional(noRollbackFor = InvitationExpiredException.class)
    public AcceptAggregateOutcome execute(
            AuthPrincipal principal,
            long invitationId,
            long workCaseId,
            byte[] tokenHash,
            long claimId) {
        LocalDateTime acceptedAt = LocalDateTime.now(clock);

        AcceptWorkCaseLockRow workCase = lockWorkCase(workCaseId, principal);
        InvitationRow invitation = lockInvitation(invitationId, workCaseId, tokenHash);

        requireUsableInvitation(invitation, acceptedAt);
        requireUnchangedTerms(invitation, workCase);
        requireAcceptableWorkCase(workCase);

        // 근무 매칭이 먼저입니다. work_contracts의 복합 FK가 근무 행의 당사자와 일급을
        // 대조하므로, WORKER를 연결하기 전에는 계약을 저장할 수 없습니다.
        if (invitationMapper.assignWorkerAndAccept(workCaseId, principal.getUserId()) != 1) {
            throw new WorkCaseLockedException(NOT_ACCEPTABLE);
        }
        if (invitationMapper.markAccepted(
                invitation.getId(),
                principal.getUserId(),
                workCase.getTermsVersion(),
                acceptedAt) != 1) {
            throw new InvitationAlreadyAcceptedException();
        }

        // 예치를 계약서보다 먼저 처리합니다. 잔액 부족은 되돌릴 수 있지만, 그 전에 PDF를
        // 만들어 두면 실패할 때마다 임시 파일을 쓰고 지우는 일이 반복됩니다. 승인 순서도
        // 잔액 확인을 Aggregate 쓰기 앞에 둡니다.
        escrowHold.hold(
                workCase.getEmployerId(),
                workCaseId,
                workCase.getDailyWage(),
                claimId,
                acceptedAt);

        AcceptedContract contract = insertContract(workCase, principal, acceptedAt);
        // 파일은 Commit 전에 임시 Key까지만 씁니다. 여기서 실패하면 수락 전체가 Rollback되고,
        // 최종 위치로 옮기는 것은 Commit 뒤입니다.
        ContractArtifactHandle artifact = contractArtifactPort.prepare(
                ContractArtifactCommand.from(contract));

        if (settlementMapper.insertWaiting(workCaseId, workCase.getDailyWage()) != 1) {
            throw new IllegalStateException("정산 예약을 생성하지 못했습니다.");
        }

        InvitationAcceptResponse response = InvitationAcceptResponse.held(workCaseId);
        // Claim 완료가 이 Transaction의 마지막 DB 변경입니다. 응답 저장이 함께 Commit되지
        // 않으면 자금은 움직였는데 Replay할 결과가 없는 상태가 생깁니다.
        claimService.complete(claimId, 200, acceptJson.writeResponseBody(response));

        return new AcceptAggregateOutcome(response, artifact);
    }

    /**
     * 근무 행을 잠그고 당사자 관계를 확인합니다.
     *
     * <p>OWNER가 자기 근무를 수락하는 것은 역할 문제가 아니라 당사자 문제라 {@code 403
     * FORBIDDEN}입니다. 인증 WORKER가 이미 그 근무의 OWNER라면 계약 상대가 자기 자신이
     * 됩니다.</p>
     */
    private AcceptWorkCaseLockRow lockWorkCase(long workCaseId, AuthPrincipal principal) {
        AcceptWorkCaseLockRow workCase = invitationMapper.lockWorkCaseForAccept(workCaseId);
        if (workCase == null) {
            // 초대는 찾았는데 근무가 없으면 참조 무결성이 깨진 상태입니다.
            throw new IllegalStateException("초대가 가리키는 근무를 찾을 수 없습니다.");
        }
        if (workCase.getEmployerId().equals(principal.getUserId())) {
            throw new ForbiddenException("본인이 등록한 근무는 수락할 수 없습니다.");
        }
        return workCase;
    }

    /**
     * 초대 행을 잠그고 Token Hash와 근무 관계를 다시 대조합니다.
     *
     * <p>잠금 전 조회와 잠금 사이에 다른 요청이 초대를 바꿀 수 있으므로, 잠근 값으로 관계를
     * 처음부터 다시 확인합니다.</p>
     */
    private InvitationRow lockInvitation(long invitationId, long workCaseId, byte[] tokenHash) {
        InvitationRow invitation = invitationMapper.lockInvitationById(invitationId);
        if (invitation == null
                || !invitation.getWorkCaseId().equals(workCaseId)
                || !java.security.MessageDigest.isEqual(invitation.getTokenHash(), tokenHash)) {
            throw new InvitationNotFoundException();
        }
        return invitation;
    }

    private void requireUsableInvitation(InvitationRow invitation, LocalDateTime now) {
        InvitationDecision decision = InvitationPolicy.decideUse(
                invitation.getStatus(), invitation.getExpiresAt(), now);
        switch (decision) {
            case USABLE -> {
                return;
            }
            case ALREADY_ACCEPTED -> throw new InvitationAlreadyAcceptedException();
            case REVOKED -> throw new InvitationRevokedException();
            case EXPIRED -> throw new InvitationExpiredException();
            case EXPIRE_NOW -> {
                // 이 전이는 410과 함께 보존해야 활성 초대 Slot이 풀립니다.
                if (invitationMapper.markExpired(invitation.getId()) != 1) {
                    throw new IllegalStateException("잠근 PENDING 초대를 만료시키지 못했습니다.");
                }
                throw new InvitationExpiredException();
            }
            case TERMS_CHANGED, UNSUPPORTED_STATUS ->
                    throw new ConflictException(UNUSABLE_INVITATION);
        }
    }

    private void requireUnchangedTerms(InvitationRow invitation, AcceptWorkCaseLockRow workCase) {
        InvitationDecision decision = InvitationPolicy.decideTerms(
                invitation.getExpectedTermsVersion(), workCase.getTermsVersion());
        if (decision == InvitationDecision.TERMS_CHANGED) {
            throw new InvitationTermsChangedException();
        }
    }

    private void requireAcceptableWorkCase(AcceptWorkCaseLockRow workCase) {
        WorkCaseDecision decision = WorkCasePolicy.decideInvitationAccept(
                workCase.getStatus(),
                workCase.getWorkerId(),
                workCase.getStartsAt(),
                LocalDateTime.now(clock));
        if (decision != WorkCaseDecision.ALLOWED) {
            // 상태·매칭·시각을 하나의 오류로 합칩니다. 어느 조건에서 걸렸는지 알려 주면
            // 아직 확정되지 않은 근무인지 같은 정보가 새어 나갑니다.
            throw new WorkCaseLockedException(NOT_ACCEPTABLE);
        }
    }

    /**
     * 확정 순간의 조건을 계약 Snapshot으로 굳힙니다.
     *
     * @return 저장한 계약 식별자와 같은 Snapshot을 소유하는 명시적 Contract 결과
     */
    private AcceptedContract insertContract(
            AcceptWorkCaseLockRow workCase,
            AuthPrincipal principal,
            LocalDateTime acceptedAt) {
        ContractPartyNamesRow names = Objects.requireNonNull(
                workContractMapper.findPartyNames(
                        workCase.getEmployerId(), principal.getUserId()),
                "계약 당사자 이름");

        ContractTermsSnapshot snapshot = ContractTermsSnapshot.builder()
                .termsVersion(workCase.getTermsVersion())
                .title(workCase.getTitle())
                .startsAt(ApiTimes.toInstant(workCase.getStartsAt()))
                .endsAt(ApiTimes.toInstant(workCase.getEndsAt()))
                .breakMinutes(workCase.getBreakMinutes())
                .breakPaid(Boolean.TRUE.equals(workCase.getBreakPaid()))
                .workplaceName(workCase.getWorkplaceName())
                .workplaceAddress(workCase.getWorkplaceAddress())
                .workplaceLatitude(workCase.getWorkplaceLatitude())
                .workplaceLongitude(workCase.getWorkplaceLongitude())
                .allowedRadiusMeters(workCase.getAllowedRadiusMeters())
                .dailyWage(workCase.getDailyWage())
                .owner(workCase.getEmployerId(), names.getEmployerName())
                .worker(principal.getUserId(), names.getWorkerName())
                .build();

        WorkContractInsertParam contract = WorkContractInsertParam.from(
                workCase.getWorkCaseId(),
                snapshot,
                acceptJson.writeSnapshot(snapshot),
                acceptedAt);

        if (workContractMapper.insert(contract) != 1) {
            throw new IllegalStateException("계약 Snapshot을 저장하지 못했습니다.");
        }
        long contractId = Objects.requireNonNull(contract.getId(), "생성된 계약 식별자");
        return AcceptedContract.of(
                workCase.getWorkCaseId(), contractId, acceptedAt, snapshot);
    }
}
