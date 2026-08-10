package com.gighub.invitation.domain;

/** 초대 사용 판단을 Application 오류와 분리한 순수 Domain 결과입니다. */
public enum InvitationDecision {
    USABLE,
    ALREADY_ACCEPTED,
    REVOKED,
    EXPIRED,
    EXPIRE_NOW,
    TERMS_CHANGED,
    UNSUPPORTED_STATUS
}
