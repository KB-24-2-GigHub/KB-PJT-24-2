package com.gighub.invitation.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InvitationPolicyTest {

    private static final LocalDateTime EXPIRES_AT =
            LocalDateTime.of(2026, 8, 10, 18, 0);

    @Test
    void pendingIsUsableOnlyBeforeTheStoredExpiryBoundary() {
        assertEquals(
                InvitationDecision.USABLE,
                InvitationPolicy.decideUse(
                        InvitationStatus.PENDING, EXPIRES_AT, EXPIRES_AT.minusNanos(1)));
        assertEquals(
                InvitationDecision.EXPIRE_NOW,
                InvitationPolicy.decideUse(
                        InvitationStatus.PENDING, EXPIRES_AT, EXPIRES_AT));
    }

    @ParameterizedTest
    @EnumSource(
            value = InvitationStatus.class,
            names = {"ACCEPTED", "REVOKED", "EXPIRED", "REJECTED"})
    void terminalAndUnsupportedStatesHaveStableMeanings(InvitationStatus status) {
        InvitationDecision expected = switch (status) {
            case ACCEPTED -> InvitationDecision.ALREADY_ACCEPTED;
            case REVOKED -> InvitationDecision.REVOKED;
            case EXPIRED -> InvitationDecision.EXPIRED;
            case REJECTED -> InvitationDecision.UNSUPPORTED_STATUS;
            case PENDING -> throw new IllegalArgumentException("PENDING은 대상이 아닙니다.");
        };

        assertEquals(
                expected,
                InvitationPolicy.decideUse(status, EXPIRES_AT, EXPIRES_AT.minusHours(1)));
    }

    @Test
    void termsVersionMismatchIsDistinctFromStatusFailure() {
        assertEquals(InvitationDecision.USABLE, InvitationPolicy.decideTerms(3, 3));
        assertEquals(InvitationDecision.TERMS_CHANGED, InvitationPolicy.decideTerms(3, 4));
    }
}
