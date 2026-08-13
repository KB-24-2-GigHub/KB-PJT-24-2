package com.gighub.settlement.service;

import com.gighub.settlement.config.SettlementSchedulerProperties;
import com.gighub.settlement.dto.ScheduledPayoutCandidate;
import com.gighub.settlement.mapper.SettlementMapper;
import com.gighub.settlement.service.result.SettlementResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.mock.env.MockEnvironment;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementScheduledPayoutSchedulerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 13, 9, 0);
    private static final int BATCH_SIZE = SettlementSchedulerProperties.DEFAULT_BATCH_SIZE;

    @Mock
    private SettlementMapper settlementMapper;

    @Mock
    private SettlementScheduledPayoutService payoutService;

    @Test
    void paysEachCandidateAndSkipsThoseAlreadyClaimed() {
        givenCandidates(candidate(1L, 10L), candidate(2L, 20L));
        when(payoutService.attemptPayout(1L, 10L, NOW))
                .thenReturn(SettlementResult.builder().settlementId(1L).build());
        when(payoutService.attemptPayout(2L, 20L, NOW)).thenReturn(null);

        scheduler().runOnce();

        verify(payoutService).attemptPayout(1L, 10L, NOW);
        verify(payoutService).attemptPayout(2L, 20L, NOW);
        verify(payoutService, never()).recordFailure(anyLong(), any(), any());
    }

    @Test
    void recordsFailureWhenPayoutAttemptThrows() {
        givenCandidates(candidate(1L, 10L));
        CannotAcquireLockException failure = new CannotAcquireLockException("lock timeout");
        when(payoutService.attemptPayout(1L, 10L, NOW)).thenThrow(failure);
        when(payoutService.recordFailure(1L, failure, NOW)).thenReturn(true);

        scheduler().runOnce();

        verify(payoutService).recordFailure(1L, failure, NOW);
    }

    @Test
    void isolatesOneCandidateFailureAndContinuesWithTheRest() {
        givenCandidates(candidate(1L, 10L), candidate(2L, 20L));
        when(payoutService.attemptPayout(1L, 10L, NOW))
                .thenThrow(new IllegalStateException("unexpected"));
        when(payoutService.recordFailure(eq(1L), any(), eq(NOW))).thenReturn(true);
        when(payoutService.attemptPayout(2L, 20L, NOW))
                .thenReturn(SettlementResult.builder().settlementId(2L).build());

        scheduler().runOnce();

        verify(payoutService).attemptPayout(2L, 20L, NOW);
    }

    @Test
    void doesNotCrashWhenRecordFailureItselfThrows() {
        givenCandidates(candidate(1L, 10L), candidate(2L, 20L));
        when(payoutService.attemptPayout(1L, 10L, NOW))
                .thenThrow(new IllegalStateException("unexpected"));
        when(payoutService.recordFailure(eq(1L), any(), eq(NOW)))
                .thenThrow(new CannotAcquireLockException("audit lock timeout"));
        when(payoutService.attemptPayout(2L, 20L, NOW))
                .thenReturn(SettlementResult.builder().settlementId(2L).build());

        scheduler().runOnce();

        verify(payoutService).attemptPayout(2L, 20L, NOW);
    }

    @Test
    void doesNotProcessAnyCandidateWhenBatchLookupFails() {
        when(settlementMapper.currentDatabaseTime()).thenReturn(NOW);
        when(settlementMapper.findScheduledPayoutCandidates(NOW, BATCH_SIZE))
                .thenThrow(new CannotAcquireLockException("batch lock"));

        scheduler().runOnce();

        verify(payoutService, times(0)).attemptPayout(anyLong(), anyLong(), any());
    }

    @Test
    void doesNotProcessAnyCandidateWhenDatabaseTimeLookupFails() {
        when(settlementMapper.currentDatabaseTime())
                .thenThrow(new CannotAcquireLockException("clock lock"));

        scheduler().runOnce();

        verify(payoutService, times(0)).attemptPayout(anyLong(), anyLong(), any());
    }

    private void givenCandidates(ScheduledPayoutCandidate... candidates) {
        when(settlementMapper.currentDatabaseTime()).thenReturn(NOW);
        when(settlementMapper.findScheduledPayoutCandidates(NOW, BATCH_SIZE))
                .thenReturn(List.of(candidates));
    }

    private ScheduledPayoutCandidate candidate(long settlementId, long workCaseId) {
        return new ScheduledPayoutCandidate(settlementId, workCaseId);
    }

    private SettlementScheduledPayoutScheduler scheduler() {
        SettlementSchedulerProperties properties =
                new SettlementSchedulerProperties(new MockEnvironment());
        return new SettlementScheduledPayoutScheduler(settlementMapper, payoutService, properties);
    }
}
