package com.gighub.settlement.service.policy;

import java.util.Objects;

/** HTTP와 무관하게 지급 거절 의미를 호출자에게 전달합니다. */
public class SettlementPayoutRejectedException extends RuntimeException {

    private final SettlementPayoutDecision decision;

    public SettlementPayoutRejectedException(SettlementPayoutDecision decision) {
        super("Settlement payout was rejected: " + decision);
        this.decision = Objects.requireNonNull(decision, "decision must not be null");
    }

    public SettlementPayoutDecision getDecision() {
        return decision;
    }
}
