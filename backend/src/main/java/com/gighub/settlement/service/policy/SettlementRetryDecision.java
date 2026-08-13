package com.gighub.settlement.service.policy;

import lombok.Getter;

import java.time.LocalDateTime;

/** {@link SettlementRetryPolicy}가 실패 한 건에 대해 내린 다음 행동입니다. */
@Getter
public final class SettlementRetryDecision {

    private final boolean retryable;
    private final String failureCode;
    private final LocalDateTime nextRetryAt;

    private SettlementRetryDecision(boolean retryable, String failureCode, LocalDateTime nextRetryAt) {
        this.retryable = retryable;
        this.failureCode = failureCode;
        this.nextRetryAt = nextRetryAt;
    }

    static SettlementRetryDecision retry(String failureCode, LocalDateTime nextRetryAt) {
        return new SettlementRetryDecision(true, failureCode, nextRetryAt);
    }

    static SettlementRetryDecision terminal(String failureCode) {
        return new SettlementRetryDecision(false, failureCode, null);
    }
}
