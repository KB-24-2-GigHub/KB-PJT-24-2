package com.gighub.settlement.service;

import com.gighub.settlement.domain.SettlementStatus;
import com.gighub.settlement.mapper.result.DisputeReviewCandidate;
import com.gighub.settlement.review.DisputeReviewDecision;
import com.gighub.settlement.review.DisputeReviewExecution;
import com.gighub.settlement.review.DisputeReviewInput;
import com.gighub.settlement.review.DisputeReviewProvider;
import com.gighub.settlement.review.DisputeReviewProviderException;
import com.gighub.settlement.review.DisputeReviewProviderFactory;
import com.gighub.settlement.review.DisputeReviewProviderResult;
import com.gighub.settlement.review.DisputeReviewResult;
import com.gighub.work.domain.WorkCaseStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisputeReviewProcessorTest {

    private static final String REQUEST_KEY = "123e4567-e89b-12d3-a456-426614174000";

    @Mock
    private DisputeReviewQueueService queueService;
    @Mock
    private DisputeReviewProviderFactory providerFactory;
    @Mock
    private DisputeReviewProvider provider;

    @Test
    void externalCallRunsBetweenClaimAndCompletionServices() {
        DisputeReviewCandidate candidate = candidate();
        DisputeReviewExecution execution = execution();
        DisputeReviewProviderResult response = response();
        when(queueService.claim(candidate)).thenReturn(execution);
        when(providerFactory.requireProvider()).thenReturn(provider);
        when(provider.review(REQUEST_KEY, execution.getInput())).thenReturn(response);

        new DisputeReviewProcessor(queueService, providerFactory).process(candidate);

        verify(queueService).claim(candidate);
        verify(provider).review(REQUEST_KEY, execution.getInput());
        verify(queueService).complete(execution, response);
    }

    @Test
    void providerFailureIsRecordedWithoutCompletion() {
        DisputeReviewCandidate candidate = candidate();
        DisputeReviewExecution execution = execution();
        when(queueService.claim(candidate)).thenReturn(execution);
        when(providerFactory.requireProvider()).thenReturn(provider);
        when(provider.review(REQUEST_KEY, execution.getInput()))
                .thenThrow(new DisputeReviewProviderException(
                        "PROVIDER_TIMEOUT", "timeout"));

        new DisputeReviewProcessor(queueService, providerFactory).process(candidate);

        verify(queueService).fail(execution, "PROVIDER_TIMEOUT");
        verify(queueService, never()).complete(execution, response());
    }

    @Test
    void missingClaimDoesNotCallProvider() {
        DisputeReviewCandidate candidate = candidate();
        when(queueService.claim(candidate)).thenReturn(null);

        new DisputeReviewProcessor(queueService, providerFactory).process(candidate);

        verify(providerFactory, never()).requireProvider();
    }

    private static DisputeReviewCandidate candidate() {
        return new DisputeReviewCandidate(41L, 91L, 11L);
    }

    private static DisputeReviewExecution execution() {
        return new DisputeReviewExecution(
                41L,
                91L,
                11L,
                REQUEST_KEY,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                new DisputeReviewInput(
                        "임금 확인",
                        "지급 여부를 확인해주세요.",
                        WorkCaseStatus.COMPLETED,
                        SettlementStatus.ON_HOLD,
                        120_000L,
                        1L)
        );
    }

    private static DisputeReviewProviderResult response() {
        return new DisputeReviewProviderResult(
                "response-1",
                new DisputeReviewResult(
                        DisputeReviewDecision.RESOLVE,
                        List.of("DEMO_REASON"),
                        "기존 정산 흐름을 재개합니다.",
                        new BigDecimal("0.900"))
        );
    }
}
