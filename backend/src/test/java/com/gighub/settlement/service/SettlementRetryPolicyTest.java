package com.gighub.settlement.service;

import com.gighub.settlement.service.policy.SettlementPayoutDecision;
import com.gighub.settlement.service.policy.SettlementPayoutRejectedException;
import com.gighub.settlement.service.policy.SettlementRetryDecision;
import com.gighub.settlement.service.policy.SettlementRetryPolicy;
import com.gighub.wallet.exception.EscrowIntegrityException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.QueryTimeoutException;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementRetryPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 13, 10, 0);

    @ParameterizedTest
    @CsvSource({
            "0,1",
            "1,5",
            "2,15",
            "3,60",
    })
    void transientFailureRetriesWithIncreasingBackoffUntilFourthAttempt(
            int currentRetryCount, long expectedBackoffMinutes) {
        SettlementRetryDecision decision = SettlementRetryPolicy.decide(
                currentRetryCount, new CannotAcquireLockException("lock timeout"), NOW);

        assertTrue(decision.isRetryable());
        assertEquals(
                SettlementRetryPolicy.FAILURE_CODE_LOCK_CONTENTION, decision.getFailureCode());
        assertEquals(NOW.plus(Duration.ofMinutes(expectedBackoffMinutes)), decision.getNextRetryAt());
    }

    @Test
    void deadlockIsAlsoTransient() {
        SettlementRetryDecision decision = SettlementRetryPolicy.decide(
                0, new DeadlockLoserDataAccessException("deadlock", null), NOW);

        assertTrue(decision.isRetryable());
    }

    @Test
    void queryTimeoutIsAlsoTransient() {
        SettlementRetryDecision decision = SettlementRetryPolicy.decide(
                0, new QueryTimeoutException("timeout"), NOW);

        assertTrue(decision.isRetryable());
    }

    @Test
    void fifthAttemptTerminatesEvenWhenTransient() {
        SettlementRetryDecision decision = SettlementRetryPolicy.decide(
                4, new CannotAcquireLockException("lock timeout"), NOW);

        assertFalse(decision.isRetryable());
        assertNull(decision.getNextRetryAt());
        assertEquals(
                SettlementRetryPolicy.FAILURE_CODE_LOCK_CONTENTION, decision.getFailureCode());
    }

    @Test
    void escrowIntegrityFailureTerminatesImmediatelyOnFirstAttempt() {
        SettlementRetryDecision decision = SettlementRetryPolicy.decide(
                0, new EscrowIntegrityException("mismatch"), NOW);

        assertFalse(decision.isRetryable());
        assertNull(decision.getNextRetryAt());
        assertEquals(
                SettlementRetryPolicy.FAILURE_CODE_INTEGRITY_VIOLATION, decision.getFailureCode());
    }

    @Test
    void integrityViolationRejectionTerminatesImmediately() {
        SettlementRetryDecision decision = SettlementRetryPolicy.decide(
                0,
                new SettlementPayoutRejectedException(SettlementPayoutDecision.INTEGRITY_VIOLATION),
                NOW);

        assertFalse(decision.isRetryable());
        assertEquals(
                SettlementRetryPolicy.FAILURE_CODE_INTEGRITY_VIOLATION, decision.getFailureCode());
    }

    @Test
    void unexpectedFailureTerminatesWithGenericCode() {
        SettlementRetryDecision decision = SettlementRetryPolicy.decide(
                0, new IllegalStateException("unexpected"), NOW);

        assertFalse(decision.isRetryable());
        assertEquals(SettlementRetryPolicy.FAILURE_CODE_UNEXPECTED, decision.getFailureCode());
    }
}
