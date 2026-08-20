package com.gighub.settlement.domain;

/** 최초 정산 금액 Snapshot을 만든 근태 결말입니다. */
public enum SettlementCalculationReason {
    CHECKED_OUT,
    NO_SHOW,
    CHECK_OUT_MISSING,
    LEGACY
}
