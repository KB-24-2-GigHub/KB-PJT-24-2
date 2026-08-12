package com.gighub.wallet.domain;

/** DB에 저장되는 에스크로 생명주기 상태입니다. */
public enum EscrowStatus {
    UNFUNDED,
    HELD,
    RELEASED,
    REFUNDED,
    ON_HOLD
}
