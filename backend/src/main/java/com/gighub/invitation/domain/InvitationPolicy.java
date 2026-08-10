package com.gighub.invitation.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/** 초대 상태·만료·조건 Version을 DB나 HTTP에 의존하지 않고 판정합니다. */
public final class InvitationPolicy {

    private InvitationPolicy() {
    }

    /** 저장 상태를 먼저 보고, PENDING이면 현재 시각의 만료 경계를 판정합니다. */
    public static InvitationDecision decideUse(
            InvitationStatus status,
            LocalDateTime expiresAt,
            LocalDateTime now) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(now, "now");
        return switch (status) {
            case ACCEPTED -> InvitationDecision.ALREADY_ACCEPTED;
            case REVOKED -> InvitationDecision.REVOKED;
            case EXPIRED -> InvitationDecision.EXPIRED;
            case REJECTED -> InvitationDecision.UNSUPPORTED_STATUS;
            case PENDING -> now.isBefore(expiresAt)
                    ? InvitationDecision.USABLE
                    : InvitationDecision.EXPIRE_NOW;
        };
    }

    /** 발급 당시 기대한 조건과 수락·조회 시점의 최신 조건 Version을 비교합니다. */
    public static InvitationDecision decideTerms(int expected, int current) {
        return expected == current
                ? InvitationDecision.USABLE
                : InvitationDecision.TERMS_CHANGED;
    }
}
