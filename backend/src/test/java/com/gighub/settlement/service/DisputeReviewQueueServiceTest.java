package com.gighub.settlement.service;

import com.gighub.common.api.ApiErrorCode;
import com.gighub.settlement.config.DisputeReviewProperties;
import com.gighub.settlement.domain.DisputeStatus;
import com.gighub.settlement.domain.SettlementStatus;
import com.gighub.settlement.dto.SettlementSnapshot;
import com.gighub.settlement.exception.DisputeReviewUnavailableException;
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
import com.gighub.settlement.review.DisputeReviewProviderFactory;
import com.gighub.settlement.review.DisputeReviewProviderResult;
import com.gighub.settlement.review.DisputeReviewResult;
import com.gighub.work.contract.WorkCaseEscrowSnapshot;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.service.WorkSettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisputeReviewQueueServiceTest {

    private static final long WORK_CASE_ID = 11L;
    private static final long SETTLEMENT_ID = 31L;
    private static final long REVIEW_ID = 41L;
    private static final long DISPUTE_ID = 91L;
    private static final String REQUEST_KEY = "123e4567-e89b-12d3-a456-426614174000";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 16, 0, 0);

    @Mock
    private WorkSettlementService workSettlementService;
    @Mock
    private SettlementMapper settlementMapper;
    @Mock
    private DisputeMapper disputeMapper;
    @Mock
    private DisputeReviewMapper reviewMapper;

    private DisputeReviewQueueService queueService;

    @BeforeEach
    void setUp() {
        DisputeReviewProperties properties = new DisputeReviewProperties(
                new MockEnvironment()
                        .withProperty(DisputeReviewProperties.MODE_KEY, "FAKE")
                        .withProperty(DisputeReviewProperties.DEMO_CONFIRMED_KEY, "true"));
        queueService = new DisputeReviewQueueService(
                workSettlementService,
                settlementMapper,
                disputeMapper,
                reviewMapper,
                new DisputeReviewProviderFactory(properties),
                properties
        );
    }

    @Test
    void disabledModeUsesDedicatedUnavailableCode() {
        DisputeReviewProperties disabledProperties = new DisputeReviewProperties(
                new MockEnvironment().withProperty(DisputeReviewProperties.MODE_KEY, "DISABLED"));
        DisputeReviewQueueService disabledService = new DisputeReviewQueueService(
                workSettlementService,
                settlementMapper,
                disputeMapper,
                reviewMapper,
                new DisputeReviewProviderFactory(disabledProperties),
                disabledProperties
        );

        DisputeReviewUnavailableException failure = assertThrows(
                DisputeReviewUnavailableException.class,
                disabledService::requireEnabled
        );

        assertEquals(ApiErrorCode.DISPUTE_REVIEW_UNAVAILABLE, failure.getCode());
    }

    @Test
    void claimLocksAggregateInFinancialOrderAndReturnsRedactedInput() {
        String inputHash = DisputeReviewInputs.sha256(input());
        givenLockedAggregate(
                DisputeReviewExecutionStatus.PENDING,
                DisputeStatus.OPEN,
                inputHash,
                null
        );
        when(reviewMapper.currentDatabaseTime()).thenReturn(NOW);
        when(reviewMapper.claimPending(REVIEW_ID, REQUEST_KEY, NOW.plusSeconds(30)))
                .thenReturn(1);

        DisputeReviewExecution execution = queueService.claim(candidate());

        assertNotNull(execution);
        assertTrue(execution.getInput().getContent().contains("REDACTED_PHONE"));
        InOrder locks = inOrder(
                workSettlementService, settlementMapper, disputeMapper, reviewMapper);
        locks.verify(workSettlementService).lockEscrowContext(WORK_CASE_ID);
        locks.verify(settlementMapper).findByWorkCaseIdForUpdate(WORK_CASE_ID);
        locks.verify(disputeMapper).findByIdForUpdate(DISPUTE_ID);
        locks.verify(reviewMapper).findByIdForUpdate(REVIEW_ID);
    }

    @Test
    void resolveClosesDisputeAndResumesOnlyExistingSettlementFlow() {
        String inputHash = DisputeReviewInputs.sha256(input());
        givenLockedAggregate(
                DisputeReviewExecutionStatus.PROCESSING,
                DisputeStatus.OPEN,
                inputHash,
                NOW.plusMinutes(1)
        );
        when(reviewMapper.currentDatabaseTime()).thenReturn(NOW);
        when(disputeMapper.transitionToClosed(
                DISPUTE_ID, DisputeStatus.RESOLVED, "기존 정산 흐름을 재개합니다."))
                .thenReturn(1);
        when(disputeMapper.findOpenIdsForUpdate(WORK_CASE_ID)).thenReturn(List.of());
        when(settlementMapper.transitionOnHoldToScheduled(SETTLEMENT_ID)).thenReturn(1);
        when(reviewMapper.complete(any())).thenReturn(1);

        boolean completed = queueService.complete(
                execution(inputHash),
                providerResponse(
                        DisputeReviewDecision.RESOLVE,
                        "기존 정산 흐름을 재개합니다."));

        assertTrue(completed);
        verify(settlementMapper).transitionOnHoldToScheduled(SETTLEMENT_ID);
        ArgumentCaptor<DisputeReviewCompletion> completion =
                ArgumentCaptor.forClass(DisputeReviewCompletion.class);
        verify(reviewMapper).complete(completion.capture());
        assertTrue(completion.getValue().getReasonCodesJson().contains("DEMO_REASON"));
    }

    @Test
    void needsMoreInfoKeepsSettlementOnHold() {
        String inputHash = DisputeReviewInputs.sha256(input());
        givenLockedAggregate(
                DisputeReviewExecutionStatus.PROCESSING,
                DisputeStatus.OPEN,
                inputHash,
                NOW.plusMinutes(1)
        );
        when(reviewMapper.currentDatabaseTime()).thenReturn(NOW);
        when(disputeMapper.transitionOpenToUnderReview(DISPUTE_ID)).thenReturn(1);
        when(reviewMapper.complete(any())).thenReturn(1);

        assertTrue(queueService.complete(
                execution(inputHash),
                providerResponse(
                        DisputeReviewDecision.NEEDS_MORE_INFO,
                        "추가 자료가 필요합니다.")));

        verify(settlementMapper, never()).transitionOnHoldToScheduled(any());
        verify(disputeMapper, never()).transitionToClosed(any(), any(), any());
    }

    @Test
    void ownerRefundDecisionCannotReverseACompletedWorkPayoutFlow() {
        String inputHash = DisputeReviewInputs.sha256(input());
        givenLockedAggregate(
                DisputeReviewExecutionStatus.PROCESSING,
                DisputeStatus.OPEN,
                inputHash,
                NOW.plusMinutes(1)
        );
        when(reviewMapper.currentDatabaseTime()).thenReturn(NOW);
        when(disputeMapper.transitionOpenToUnderReview(DISPUTE_ID)).thenReturn(1);
        when(reviewMapper.complete(any())).thenReturn(1);

        assertTrue(queueService.complete(
                execution(inputHash),
                providerResponse(
                        DisputeReviewDecision.REJECT,
                        "사장님 환불 방향입니다.")));

        ArgumentCaptor<DisputeReviewCompletion> completion =
                ArgumentCaptor.forClass(DisputeReviewCompletion.class);
        verify(reviewMapper).complete(completion.capture());
        assertEquals(
                DisputeReviewDecision.NEEDS_MORE_INFO,
                completion.getValue().getDecision());
        verify(disputeMapper, never()).transitionToClosed(any(), any(), any());
        verify(settlementMapper, never()).transitionOnHoldToScheduled(any());
    }

    @Test
    void ownerRefundDecisionCanCloseCheckoutMissingDisputeBeforeManualRefund() {
        DisputeReviewInput missingInput = new DisputeReviewInput(
                input().getTitle(),
                input().getContent(),
                WorkCaseStatus.CHECK_OUT_MISSING,
                SettlementStatus.WAITING,
                input().getAgreedWage(),
                1L);
        String inputHash = DisputeReviewInputs.sha256(missingInput);
        givenLockedAggregate(
                DisputeReviewExecutionStatus.PROCESSING,
                DisputeStatus.OPEN,
                inputHash,
                NOW.plusMinutes(1));
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID))
                .thenReturn(workCase().toBuilder()
                        .status(WorkCaseStatus.CHECK_OUT_MISSING)
                        .successfulCheckInCount(1L)
                        .successfulCheckOutCount(0L)
                        .build());
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement().toBuilder()
                        .status(SettlementStatus.WAITING)
                        .dueAt(null)
                        .build());
        when(reviewMapper.currentDatabaseTime()).thenReturn(NOW);
        when(reviewMapper.complete(any())).thenReturn(1);
        when(disputeMapper.transitionToClosed(
                DISPUTE_ID, DisputeStatus.REJECTED, "사장님 환불 승인을 재개합니다."))
                .thenReturn(1);
        when(disputeMapper.findOpenIdsForUpdate(WORK_CASE_ID)).thenReturn(List.of());

        assertTrue(queueService.complete(
                execution(inputHash, missingInput),
                providerResponse(
                        DisputeReviewDecision.REJECT,
                        "사장님 환불 승인을 재개합니다.")));

        verify(disputeMapper).transitionToClosed(
                DISPUTE_ID, DisputeStatus.REJECTED, "사장님 환불 승인을 재개합니다.");
        verify(settlementMapper, never()).transitionOnHoldToScheduled(any());
    }

    @Test
    void invalidProviderOutputAlsoRetriesWithinConfiguredLimitAndKeepsHold() {
        String inputHash = DisputeReviewInputs.sha256(input());
        givenLockedAggregate(
                DisputeReviewExecutionStatus.PROCESSING,
                DisputeStatus.OPEN,
                inputHash,
                NOW.plusMinutes(1)
        );
        when(disputeMapper.transitionOpenToUnderReview(DISPUTE_ID)).thenReturn(1);
        when(reviewMapper.currentDatabaseTime()).thenReturn(NOW);
        when(reviewMapper.markFailed(REVIEW_ID, REQUEST_KEY, "INVALID_PROVIDER_OUTPUT"))
                .thenReturn(1);
        when(reviewMapper.countByDisputeId(DISPUTE_ID)).thenReturn(1L);
        when(reviewMapper.insertPending(any())).thenAnswer(invocation -> {
            ((DisputeReviewInsert) invocation.getArgument(0)).setReviewId(42L);
            return 1;
        });

        assertTrue(queueService.fail(execution(inputHash), "INVALID_PROVIDER_OUTPUT"));

        verify(settlementMapper, never()).transitionOnHoldToScheduled(any());
        verify(reviewMapper).insertPending(any());
    }

    @Test
    void transientProviderFailureCreatesFreshPendingRetryAndKeepsHold() {
        String inputHash = DisputeReviewInputs.sha256(input());
        givenLockedAggregate(
                DisputeReviewExecutionStatus.PROCESSING,
                DisputeStatus.OPEN,
                inputHash,
                NOW.plusMinutes(1)
        );
        when(disputeMapper.transitionOpenToUnderReview(DISPUTE_ID)).thenReturn(1);
        when(reviewMapper.currentDatabaseTime()).thenReturn(NOW);
        when(reviewMapper.markFailed(REVIEW_ID, REQUEST_KEY, "PROVIDER_5XX"))
                .thenReturn(1);
        when(reviewMapper.countByDisputeId(DISPUTE_ID)).thenReturn(1L);
        when(reviewMapper.insertPending(any())).thenAnswer(invocation -> {
            ((DisputeReviewInsert) invocation.getArgument(0)).setReviewId(42L);
            return 1;
        });

        assertTrue(queueService.fail(execution(inputHash), "PROVIDER_5XX"));

        ArgumentCaptor<DisputeReviewInsert> retry =
                ArgumentCaptor.forClass(DisputeReviewInsert.class);
        verify(reviewMapper).insertPending(retry.capture());
        assertNotEquals(REQUEST_KEY, retry.getValue().getRequestKey());
        assertEquals(inputHash, retry.getValue().getInputHash());
        verify(settlementMapper, never()).transitionOnHoldToScheduled(any());
    }

    @Test
    void expiredLeaseFailsOldExecutionAndCreatesFreshPendingRetry() {
        String inputHash = DisputeReviewInputs.sha256(input());
        givenLockedAggregate(
                DisputeReviewExecutionStatus.PROCESSING,
                DisputeStatus.OPEN,
                inputHash,
                NOW.minusNanos(1)
        );
        when(reviewMapper.currentDatabaseTime()).thenReturn(NOW);
        when(disputeMapper.transitionOpenToUnderReview(DISPUTE_ID)).thenReturn(1);
        when(reviewMapper.markFailed(REVIEW_ID, REQUEST_KEY, "WORKER_LEASE_EXPIRED"))
                .thenReturn(1);
        when(reviewMapper.countByDisputeId(DISPUTE_ID)).thenReturn(1L);
        when(reviewMapper.insertPending(any())).thenAnswer(invocation -> {
            ((DisputeReviewInsert) invocation.getArgument(0)).setReviewId(42L);
            return 1;
        });

        assertNull(queueService.claim(candidate()));

        verify(reviewMapper, never()).claimPending(any(), any(), any());
        verify(reviewMapper).insertPending(any());
        verify(settlementMapper, never()).transitionOnHoldToScheduled(any());
    }

    @Test
    void responseArrivingAfterLeaseExpiryCreatesFreshRetryInsteadOfPermanentHold() {
        String inputHash = DisputeReviewInputs.sha256(input());
        givenLockedAggregate(
                DisputeReviewExecutionStatus.PROCESSING,
                DisputeStatus.OPEN,
                inputHash,
                NOW.minusNanos(1)
        );
        when(reviewMapper.currentDatabaseTime()).thenReturn(NOW);
        when(disputeMapper.transitionOpenToUnderReview(DISPUTE_ID)).thenReturn(1);
        when(reviewMapper.markFailed(REVIEW_ID, REQUEST_KEY, "WORKER_LEASE_EXPIRED"))
                .thenReturn(1);
        when(reviewMapper.countByDisputeId(DISPUTE_ID)).thenReturn(1L);
        when(reviewMapper.insertPending(any())).thenAnswer(invocation -> {
            ((DisputeReviewInsert) invocation.getArgument(0)).setReviewId(42L);
            return 1;
        });

        assertFalse(queueService.complete(
                execution(inputHash),
                providerResponse(DisputeReviewDecision.RESOLVE, "임대 만료 뒤 도착한 결과입니다.")));

        verify(reviewMapper).markFailed(REVIEW_ID, REQUEST_KEY, "WORKER_LEASE_EXPIRED");
        verify(reviewMapper).insertPending(any());
        verify(reviewMapper, never()).complete(any());
        verify(disputeMapper, never()).transitionToClosed(any(), any(), any());
        verify(settlementMapper, never()).transitionOnHoldToScheduled(any());
    }

    @Test
    void failureReportedAfterLeaseExpiryUsesLeaseFailureAndCreatesFreshRetry() {
        String inputHash = DisputeReviewInputs.sha256(input());
        givenLockedAggregate(
                DisputeReviewExecutionStatus.PROCESSING,
                DisputeStatus.OPEN,
                inputHash,
                NOW.minusNanos(1)
        );
        when(reviewMapper.currentDatabaseTime()).thenReturn(NOW);
        when(disputeMapper.transitionOpenToUnderReview(DISPUTE_ID)).thenReturn(1);
        when(reviewMapper.markFailed(REVIEW_ID, REQUEST_KEY, "WORKER_LEASE_EXPIRED"))
                .thenReturn(1);
        when(reviewMapper.countByDisputeId(DISPUTE_ID)).thenReturn(1L);
        when(reviewMapper.insertPending(any())).thenAnswer(invocation -> {
            ((DisputeReviewInsert) invocation.getArgument(0)).setReviewId(42L);
            return 1;
        });

        assertFalse(queueService.fail(execution(inputHash), "PROVIDER_5XX"));

        verify(reviewMapper).markFailed(REVIEW_ID, REQUEST_KEY, "WORKER_LEASE_EXPIRED");
        verify(reviewMapper).insertPending(any());
        verify(reviewMapper, never()).complete(any());
        verify(settlementMapper, never()).transitionOnHoldToScheduled(any());
    }

    @Test
    void settlementStateChangeAfterClaimCreatesFreshRetryInsteadOfPermanentHold() {
        String inputHash = DisputeReviewInputs.sha256(input());
        givenLockedAggregate(
                DisputeReviewExecutionStatus.PROCESSING,
                DisputeStatus.OPEN,
                inputHash,
                NOW.plusMinutes(1)
        );
        when(reviewMapper.currentDatabaseTime()).thenReturn(NOW);
        when(disputeMapper.transitionOpenToUnderReview(DISPUTE_ID)).thenReturn(1);
        when(reviewMapper.markFailed(REVIEW_ID, REQUEST_KEY, "STALE_REVIEW_RESPONSE"))
                .thenReturn(1);
        when(reviewMapper.countByDisputeId(DISPUTE_ID)).thenReturn(1L);
        when(reviewMapper.insertPending(any())).thenAnswer(invocation -> {
            ((DisputeReviewInsert) invocation.getArgument(0)).setReviewId(42L);
            return 1;
        });
        DisputeReviewInput claimedInput = new DisputeReviewInput(
                input().getTitle(),
                input().getContent(),
                WorkCaseStatus.IN_PROGRESS,
                SettlementStatus.WAITING,
                input().getAgreedWage(),
                input().getSuccessfulCheckInCount()
        );

        assertFalse(queueService.complete(
                execution(inputHash, claimedInput),
                providerResponse(DisputeReviewDecision.RESOLVE, "이전 상태를 기준으로 한 결과입니다.")));

        verify(reviewMapper).markFailed(REVIEW_ID, REQUEST_KEY, "STALE_REVIEW_RESPONSE");
        verify(reviewMapper).insertPending(any());
        verify(reviewMapper, never()).complete(any());
        verify(disputeMapper, never()).transitionToClosed(any(), any(), any());
        verify(settlementMapper, never()).transitionOnHoldToScheduled(any());
    }

    @Test
    void transientFailureStopsRetryingAtConfiguredAttemptLimit() {
        String inputHash = DisputeReviewInputs.sha256(input());
        givenLockedAggregate(
                DisputeReviewExecutionStatus.PROCESSING,
                DisputeStatus.UNDER_REVIEW,
                inputHash,
                NOW.plusMinutes(1)
        );
        when(reviewMapper.markFailed(REVIEW_ID, REQUEST_KEY, "PROVIDER_TIMEOUT"))
                .thenReturn(1);
        when(reviewMapper.currentDatabaseTime()).thenReturn(NOW);
        when(reviewMapper.countByDisputeId(DISPUTE_ID)).thenReturn(3L);

        assertTrue(queueService.fail(execution(inputHash), "PROVIDER_TIMEOUT"));

        verify(reviewMapper, never()).insertPending(any());
        verify(disputeMapper, never()).transitionOpenToUnderReview(any());
        verify(settlementMapper, never()).transitionOnHoldToScheduled(any());
    }

    @Test
    void retryCanCompleteNeedsMoreInfoWhileDisputeIsAlreadyUnderReview() {
        String inputHash = DisputeReviewInputs.sha256(input());
        givenLockedAggregate(
                DisputeReviewExecutionStatus.PROCESSING,
                DisputeStatus.UNDER_REVIEW,
                inputHash,
                NOW.plusMinutes(1)
        );
        when(reviewMapper.currentDatabaseTime()).thenReturn(NOW);
        when(reviewMapper.complete(any())).thenReturn(1);

        assertTrue(queueService.complete(
                execution(inputHash),
                providerResponse(
                        DisputeReviewDecision.NEEDS_MORE_INFO,
                        "추가 자료가 필요합니다.")));

        verify(disputeMapper, never()).transitionOpenToUnderReview(any());
        verify(settlementMapper, never()).transitionOnHoldToScheduled(any());
    }

    @Test
    void duplicateCompletionIsIgnoredAfterAuditRowAlreadyClosed() {
        String inputHash = DisputeReviewInputs.sha256(input());
        givenLockedAggregate(
                DisputeReviewExecutionStatus.COMPLETED,
                DisputeStatus.RESOLVED,
                inputHash,
                null
        );

        assertFalse(queueService.complete(
                execution(inputHash),
                providerResponse(DisputeReviewDecision.RESOLVE, "이미 처리됐습니다.")));

        verify(disputeMapper, never()).transitionToClosed(any(), any(), any());
        verify(reviewMapper, never()).complete(any());
    }

    private void givenLockedAggregate(
            DisputeReviewExecutionStatus reviewStatus,
            DisputeStatus disputeStatus,
            String inputHash,
            LocalDateTime leaseUntil) {
        when(workSettlementService.lockEscrowContext(WORK_CASE_ID)).thenReturn(workCase());
        when(settlementMapper.findByWorkCaseIdForUpdate(WORK_CASE_ID))
                .thenReturn(settlement());
        when(disputeMapper.findByIdForUpdate(DISPUTE_ID)).thenReturn(new DisputeSnapshot(
                DISPUTE_ID,
                WORK_CASE_ID,
                "임금 확인",
                "010-1234-5678 연락 후 지급 여부를 확인해주세요.",
                disputeStatus
        ));
        when(reviewMapper.findByIdForUpdate(REVIEW_ID)).thenReturn(review(
                reviewStatus, inputHash, leaseUntil));
    }

    private static DisputeReviewCandidate candidate() {
        return new DisputeReviewCandidate(REVIEW_ID, DISPUTE_ID, WORK_CASE_ID);
    }

    private static DisputeReviewExecution execution(String inputHash) {
        return execution(inputHash, input());
    }

    private static DisputeReviewExecution execution(
            String inputHash,
            DisputeReviewInput claimedInput) {
        return new DisputeReviewExecution(
                REVIEW_ID,
                DISPUTE_ID,
                WORK_CASE_ID,
                REQUEST_KEY,
                inputHash,
                DisputeReviewInputs.snapshotSha256(claimedInput),
                claimedInput);
    }

    private static DisputeReviewProviderResult providerResponse(
            DisputeReviewDecision decision,
            String summary) {
        return new DisputeReviewProviderResult(
                "provider-response-1",
                new DisputeReviewResult(
                        decision,
                        List.of("DEMO_REASON"),
                        summary,
                        new BigDecimal("0.900"))
        );
    }

    private static DisputeReviewInput input() {
        return new DisputeReviewInput(
                "임금 확인",
                "010-1234-5678 연락 후 지급 여부를 확인해주세요.",
                WorkCaseStatus.COMPLETED,
                SettlementStatus.ON_HOLD,
                120_000L,
                1L
        );
    }

    private static WorkCaseEscrowSnapshot workCase() {
        return WorkCaseEscrowSnapshot.builder()
                .workCaseId(WORK_CASE_ID)
                .employerId(21L)
                .workerId(22L)
                .agreedWage(120_000L)
                .status(WorkCaseStatus.COMPLETED)
                .successfulCheckInCount(1L)
                .successfulCheckOutCount(1L)
                .build();
    }

    private static SettlementSnapshot settlement() {
        return SettlementSnapshot.builder()
                .settlementId(SETTLEMENT_ID)
                .workCaseId(WORK_CASE_ID)
                .amount(120_000L)
                .status(SettlementStatus.ON_HOLD)
                .retryCount(0)
                .build();
    }

    private static DisputeReviewExecutionRow review(
            DisputeReviewExecutionStatus status,
            String inputHash,
            LocalDateTime leaseUntil) {
        return new DisputeReviewExecutionRow(
                REVIEW_ID,
                DISPUTE_ID,
                REQUEST_KEY,
                status,
                "SIMULATED_LLM",
                "FAKE",
                "deterministic-dispute-demo-v1",
                "dispute-review-v1",
                inputHash,
                null,
                null,
                null,
                null,
                null,
                null,
                leaseUntil,
                status == DisputeReviewExecutionStatus.PENDING ? null : NOW.minusSeconds(1),
                status == DisputeReviewExecutionStatus.COMPLETED ? NOW : null
        );
    }
}
