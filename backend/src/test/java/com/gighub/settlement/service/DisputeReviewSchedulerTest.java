package com.gighub.settlement.service;

import com.gighub.settlement.config.DisputeReviewProperties;
import com.gighub.settlement.mapper.DisputeReviewMapper;
import com.gighub.settlement.mapper.result.DisputeReviewCandidate;
import com.gighub.settlement.review.DisputeReviewProviderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisputeReviewSchedulerTest {

    @Mock
    private DisputeReviewMapper reviewMapper;
    @Mock
    private DisputeReviewProcessor processor;
    @Mock
    private DisputeReviewProviderFactory providerFactory;
    @Mock
    private DisputeReviewProperties properties;
    @Mock
    private ExecutorService executor;

    @Test
    void enabledSchedulerSubmitsCandidatesWithoutCallingProviderOnSchedulerThread() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 16, 0, 0);
        when(providerFactory.isEnabled()).thenReturn(true);
        when(properties.getBatchSize()).thenReturn(20);
        when(reviewMapper.currentDatabaseTime()).thenReturn(now);
        when(reviewMapper.findCandidates(now, 20)).thenReturn(List.of(
                new DisputeReviewCandidate(41L, 91L, 11L),
                new DisputeReviewCandidate(42L, 92L, 12L)
        ));

        scheduler().runOnce();

        verify(executor, times(2)).execute(any(Runnable.class));
        verify(processor, never()).process(any());
    }

    @Test
    void disabledSchedulerDoesNotTouchReviewTable() {
        when(providerFactory.isEnabled()).thenReturn(false);

        scheduler().runOnce();

        verify(reviewMapper, never()).currentDatabaseTime();
        verify(executor, never()).execute(any());
    }

    @Test
    void candidateAlreadySubmittedByThisInstanceIsNotQueuedAgain() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 16, 0, 0);
        DisputeReviewCandidate candidate = new DisputeReviewCandidate(41L, 91L, 11L);
        when(providerFactory.isEnabled()).thenReturn(true);
        when(properties.getBatchSize()).thenReturn(20);
        when(reviewMapper.currentDatabaseTime()).thenReturn(now);
        when(reviewMapper.findCandidates(now, 20)).thenReturn(List.of(candidate));
        DisputeReviewScheduler scheduler = scheduler();

        scheduler.runOnce();
        scheduler.runOnce();

        verify(executor, times(1)).execute(any(Runnable.class));
    }

    private DisputeReviewScheduler scheduler() {
        return new DisputeReviewScheduler(
                reviewMapper, processor, providerFactory, properties, executor);
    }
}
