package com.gighub.settlement.service.policy;

/** 지급 정책의 외부 오류나 저장 동작과 독립적인 판단 결과입니다. */
public enum SettlementPayoutDecision {
    ALLOWED,
    RESOURCE_NOT_FOUND,
    NOT_READY,
    ON_HOLD,
    ALREADY_PROCESSED,
    INTEGRITY_VIOLATION
}
