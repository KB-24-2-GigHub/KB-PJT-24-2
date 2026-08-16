package com.gighub.settlement.service;

import com.gighub.settlement.config.DisputeReviewProperties;
import com.gighub.settlement.domain.DisputeStatus;
import com.gighub.settlement.domain.SettlementStatus;
import com.gighub.settlement.dto.SettlementSnapshot;
import com.gighub.settlement.mapper.DisputeMapper;
import com.gighub.settlement.mapper.DisputeReviewMapper;
import com.gighub.settlement.mapper.SettlementMapper;
import com.gighub.settlement.mapper.command.DisputeReviewCompletion;
import com.gighub.settlement.mapper.command.DisputeReviewInsert;
import com.gighub.settlement.mapper.result.DisputeReviewCandidate;
import com.gighub.settlement.mapper.result.DisputeReviewExecutionRow;
import com.gighub.settlement.mapper.result.DisputeSnapshot;
import com.gighub.settlement.review.DisputeReviewDecision;
import com.gighub.settlement.review.DisputeReviewExecution;
import com.gighub.settlement.review.DisputeReviewExecutionStatus;
import com.gighub.settlement.review.DisputeReviewInput;
import com.gighub.settlement.review.DisputeReviewInputs;
import com.gighub.settlement.review.DisputeReviewJsonCodec;
import com.gighub.settlement.review.DisputeReviewProvider;
import com.gighub.settlement.review.DisputeReviewProviderFactory;
import com.gighub.settlement.review.DisputeReviewProviderResult;
import com.gighub.settlement.review.DisputeReviewResult;
import com.gighub.settlement.review.DisputeReviewResults;
import com.gighub.settlement.service.command.DisputeReviewEnqueueCommand;
import com.gighub.work.contract.WorkCaseEscrowSnapshot;
import com.gighub.work.service.WorkSettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 분쟁 검토 Queue의 생성·선점·결과 반영을 각각 짧은 Transaction으로 수행합니다. */
@Service
@RequiredArgsConstructor
public class DisputeReviewQueueService {

    private static final String SOURCE = "SIMULATED_LLM";
    private static final Pattern FAILURE_CODE = Pattern.compile("[A-Z0-9_]{1,50}");
    private static final Set<String> RETRYABLE_FAILURE_CODES = Set.of(
            "WORKER_LEASE_EXPIRED",
            "PROVIDER_TIMEOUT",
            "PROVIDER_INTERRUPTED",
            "PROVIDER_TRANSPORT_ERROR",
            "PROVIDER_RATE_LIMIT",
            "PROVIDER_5XX"
    );

    private final WorkSettlementService workSettlementService;
    private final SettlementMapper settlementMapper;
    private final DisputeMapper disputeMapper;
    private final DisputeReviewMapper reviewMapper;
    private final DisputeReviewProviderFactory providerFactory;
    private final DisputeReviewProperties properties;

    /** 분쟁과 같은 Transaction에 PENDING 행을 넣어 Commit 뒤 Worker가 찾을 수 있게 합니다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(DisputeReviewEnqueueCommand command) {
        if (!providerFactory.isEnabled()) {
            return;
        }
        Objects.requireNonNull(command, "command");
        WorkCaseEscrowSnapshot workCase = Objects.requireNonNull(command.getWorkCase(), "workCase");
        DisputeReviewInput input = DisputeReviewInputs.sanitize(new DisputeReviewInput(
                command.getTitle(),
                command.getContent(),
                workCase.getStatus(),
                command.getSettlementStatus(),
                workCase.getAgreedWage(),
                workCase.getSuccessfulCheckInCount()
        ));
        insertPending(command.getDisputeId(), DisputeReviewInputs.sha256(input));
    }

    /** Work → Settlement → Dispute → Review 순으로 선점하고 외부 호출용 값만 반환합니다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DisputeReviewExecution claim(DisputeReviewCandidate candidate) {
        if (!providerFactory.isEnabled()) {
            return null;
        }
        LockedAggregate aggregate = lockAggregate(candidate);
        if (aggregate == null || aggregate.review().getStatus() == DisputeReviewExecutionStatus.COMPLETED
                || aggregate.review().getStatus() == DisputeReviewExecutionStatus.FAILED) {
            return null;
        }
        LocalDateTime now = reviewMapper.currentDatabaseTime();
        if (aggregate.review().getStatus() == DisputeReviewExecutionStatus.PROCESSING) {
            if (isLeaseExpired(aggregate.review(), now)) {
                failLocked(aggregate, "WORKER_LEASE_EXPIRED");
                enqueueRetryIfAllowed(aggregate, "WORKER_LEASE_EXPIRED");
            }
            return null;
        }

        DisputeReviewInput input = inputOf(aggregate);
        String inputHash = DisputeReviewInputs.sha256(input);
        if (!isCurrentAndApplicable(aggregate, inputHash)) {
            failLocked(aggregate, "STALE_REVIEW_INPUT");
            return null;
        }
        LocalDateTime leaseUntil = now.plus(properties.getLease());
        if (reviewMapper.claimPending(
                aggregate.review().getReviewId(),
                aggregate.review().getRequestKey(),
                leaseUntil) != 1) {
            return null;
        }
        return new DisputeReviewExecution(
                aggregate.review().getReviewId(),
                aggregate.dispute().getDisputeId(),
                aggregate.workCase().getWorkCaseId(),
                aggregate.review().getRequestKey(),
                aggregate.review().getInputHash(),
                input
        );
    }

    /** Provider 결과를 현재 Snapshot과 다시 맞춘 뒤 한 번만 반영합니다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(
            DisputeReviewExecution execution,
            DisputeReviewProviderResult providerResponse) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(providerResponse, "providerResponse");
        LockedAggregate aggregate = lockAggregate(candidateOf(execution));
        if (!matchesProcessingExecution(aggregate, execution)) {
            return false;
        }
        LocalDateTime now = reviewMapper.currentDatabaseTime();
        DisputeReviewInput currentInput = inputOf(aggregate);
        String currentInputHash = DisputeReviewInputs.sha256(currentInput);
        if (isLeaseExpired(aggregate.review(), now)
                || !execution.getInputHash().equals(currentInputHash)
                || !isCurrentAndApplicable(aggregate, currentInputHash)) {
            failLocked(aggregate, "STALE_REVIEW_RESPONSE");
            return false;
        }

        DisputeReviewResult result = DisputeReviewResults.validate(providerResponse.getResult());
        applyDecision(aggregate, result);
        DisputeReviewCompletion completion = DisputeReviewCompletion.builder()
                .reviewId(execution.getReviewId())
                .requestKey(execution.getRequestKey())
                .decision(result.getDecision())
                .reasonCodesJson(DisputeReviewJsonCodec.writeReasonCodes(result.getReasonCodes()))
                .summary(result.getSummary())
                .confidence(result.getConfidence())
                .providerResponseId(providerResponse.getProviderResponseId())
                .build();
        if (reviewMapper.complete(completion) != 1) {
            throw new IllegalStateException("분쟁 검토 완료 이력을 저장하지 못했습니다.");
        }
        return true;
    }

    /** Provider 실패는 감사하고 분쟁을 UNDER_REVIEW로 남겨 보류를 유지합니다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean fail(DisputeReviewExecution execution, String failureCode) {
        Objects.requireNonNull(execution, "execution");
        LockedAggregate aggregate = lockAggregate(candidateOf(execution));
        if (!matchesProcessingExecution(aggregate, execution)) {
            return false;
        }
        String normalizedFailureCode = normalizeFailureCode(failureCode);
        failLocked(aggregate, normalizedFailureCode);
        enqueueRetryIfAllowed(aggregate, normalizedFailureCode);
        return true;
    }

    private LockedAggregate lockAggregate(DisputeReviewCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        WorkCaseEscrowSnapshot workCase =
                workSettlementService.lockEscrowContext(candidate.getWorkCaseId());
        SettlementSnapshot settlement =
                settlementMapper.findByWorkCaseIdForUpdate(candidate.getWorkCaseId());
        DisputeSnapshot dispute = disputeMapper.findByIdForUpdate(candidate.getDisputeId());
        DisputeReviewExecutionRow review = reviewMapper.findByIdForUpdate(candidate.getReviewId());
        if (review == null) {
            return null;
        }
        if (workCase == null || settlement == null || dispute == null
                || !candidate.getWorkCaseId().equals(dispute.getWorkCaseId())
                || !candidate.getDisputeId().equals(review.getDisputeId())) {
            throw new IllegalStateException("분쟁 검토 Aggregate 연결이 올바르지 않습니다.");
        }
        return new LockedAggregate(workCase, settlement, dispute, review);
    }

    private boolean isCurrentAndApplicable(
            LockedAggregate aggregate,
            String currentInputHash) {
        DisputeReviewProvider provider = providerFactory.requireProvider();
        return (aggregate.dispute().getStatus() == DisputeStatus.OPEN
                || aggregate.dispute().getStatus() == DisputeStatus.UNDER_REVIEW)
                && SOURCE.equals(aggregate.review().getSource())
                && provider.providerName().equals(aggregate.review().getProvider())
                && provider.modelName().equals(aggregate.review().getModel())
                && provider.promptVersion().equals(aggregate.review().getPromptVersion())
                && aggregate.review().getInputHash().equals(currentInputHash);
    }

    private void applyDecision(LockedAggregate aggregate, DisputeReviewResult result) {
        if (result.getDecision() == DisputeReviewDecision.NEEDS_MORE_INFO) {
            if (aggregate.dispute().getStatus() == DisputeStatus.OPEN
                    && disputeMapper.transitionOpenToUnderReview(
                    aggregate.dispute().getDisputeId()) != 1) {
                throw new IllegalStateException("분쟁 추가 검토 상태를 반영하지 못했습니다.");
            }
            if (aggregate.dispute().getStatus() != DisputeStatus.OPEN
                    && aggregate.dispute().getStatus() != DisputeStatus.UNDER_REVIEW) {
                throw new IllegalStateException("종료된 분쟁에는 추가 검토 결과를 반영할 수 없습니다.");
            }
            return;
        }

        DisputeStatus targetStatus = result.getDecision() == DisputeReviewDecision.RESOLVE
                ? DisputeStatus.RESOLVED
                : DisputeStatus.REJECTED;
        if (disputeMapper.transitionToClosed(
                aggregate.dispute().getDisputeId(), targetStatus, result.getSummary()) != 1) {
            throw new IllegalStateException("분쟁 종료 상태를 반영하지 못했습니다.");
        }
        if (disputeMapper.findOpenIdsForUpdate(aggregate.workCase().getWorkCaseId()).isEmpty()
                && aggregate.settlement().getStatus() == SettlementStatus.ON_HOLD
                && settlementMapper.transitionOnHoldToScheduled(
                        aggregate.settlement().getSettlementId()) != 1) {
            throw new IllegalStateException("분쟁 종료 후 정산 재개에 실패했습니다.");
        }
    }

    private void failLocked(LockedAggregate aggregate, String failureCode) {
        if (aggregate.dispute().getStatus() == DisputeStatus.OPEN
                && disputeMapper.transitionOpenToUnderReview(
                        aggregate.dispute().getDisputeId()) != 1) {
            throw new IllegalStateException("Provider 실패 후 분쟁 보류 상태를 저장하지 못했습니다.");
        }
        if (reviewMapper.markFailed(
                aggregate.review().getReviewId(),
                aggregate.review().getRequestKey(),
                normalizeFailureCode(failureCode)) != 1) {
            throw new IllegalStateException("Provider 실패 이력을 저장하지 못했습니다.");
        }
    }

    private void enqueueRetryIfAllowed(LockedAggregate aggregate, String failureCode) {
        if (!RETRYABLE_FAILURE_CODES.contains(failureCode)) {
            return;
        }
        DisputeReviewInput currentInput = inputOf(aggregate);
        String currentInputHash = DisputeReviewInputs.sha256(currentInput);
        if (!isCurrentAndApplicable(aggregate, currentInputHash)
                || reviewMapper.countByDisputeId(
                aggregate.dispute().getDisputeId()) >= properties.getMaxAttempts()) {
            return;
        }
        insertPending(aggregate.dispute().getDisputeId(), currentInputHash);
    }

    private void insertPending(Long disputeId, String inputHash) {
        DisputeReviewProvider provider = providerFactory.requireProvider();
        DisputeReviewInsert insert = DisputeReviewInsert.builder()
                .disputeId(disputeId)
                .requestKey(UUID.randomUUID().toString())
                .provider(provider.providerName())
                .model(provider.modelName())
                .promptVersion(provider.promptVersion())
                .inputHash(inputHash)
                .build();
        if (reviewMapper.insertPending(insert) != 1 || insert.getReviewId() == null) {
            throw new IllegalStateException("분쟁 검토 작업을 예약하지 못했습니다.");
        }
    }

    private DisputeReviewInput inputOf(LockedAggregate aggregate) {
        return DisputeReviewInputs.sanitize(new DisputeReviewInput(
                aggregate.dispute().getTitle(),
                aggregate.dispute().getContent(),
                aggregate.workCase().getStatus(),
                aggregate.settlement().getStatus(),
                aggregate.workCase().getAgreedWage(),
                aggregate.workCase().getSuccessfulCheckInCount()
        ));
    }

    private static boolean matchesProcessingExecution(
            LockedAggregate aggregate,
            DisputeReviewExecution execution) {
        return aggregate != null
                && aggregate.review().getStatus() == DisputeReviewExecutionStatus.PROCESSING
                && execution.getRequestKey().equals(aggregate.review().getRequestKey())
                && execution.getInputHash().equals(aggregate.review().getInputHash());
    }

    private static boolean isLeaseExpired(
            DisputeReviewExecutionRow review,
            LocalDateTime now) {
        return review.getLeaseUntil() == null || !review.getLeaseUntil().isAfter(now);
    }

    private static String normalizeFailureCode(String failureCode) {
        String normalized = failureCode == null ? "UNEXPECTED_PROVIDER_ERROR" : failureCode.trim();
        if (!FAILURE_CODE.matcher(normalized).matches()) {
            return "UNEXPECTED_PROVIDER_ERROR";
        }
        return normalized;
    }

    private static DisputeReviewCandidate candidateOf(DisputeReviewExecution execution) {
        return new DisputeReviewCandidate(
                execution.getReviewId(), execution.getDisputeId(), execution.getWorkCaseId());
    }

    private record LockedAggregate(
            WorkCaseEscrowSnapshot workCase,
            SettlementSnapshot settlement,
            DisputeSnapshot dispute,
            DisputeReviewExecutionRow review) {
    }
}
