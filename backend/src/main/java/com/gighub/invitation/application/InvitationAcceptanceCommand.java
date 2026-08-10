package com.gighub.invitation.application;

import java.util.Objects;

/** 짧은 수락 Aggregate Transaction에 필요한 서버 유도 값만 전달합니다. */
public final class InvitationAcceptanceCommand {

    private final long workerId;
    private final long invitationId;
    private final long workCaseId;
    private final byte[] tokenHash;
    private final long claimId;

    private InvitationAcceptanceCommand(
            long workerId,
            long invitationId,
            long workCaseId,
            byte[] tokenHash,
            long claimId) {
        this.workerId = workerId;
        this.invitationId = invitationId;
        this.workCaseId = workCaseId;
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash").clone();
        this.claimId = claimId;
    }

    public static InvitationAcceptanceCommand of(
            long workerId,
            long invitationId,
            long workCaseId,
            byte[] tokenHash,
            long claimId) {
        return new InvitationAcceptanceCommand(
                workerId, invitationId, workCaseId, tokenHash, claimId);
    }

    public long getWorkerId() {
        return workerId;
    }

    public long getInvitationId() {
        return invitationId;
    }

    public long getWorkCaseId() {
        return workCaseId;
    }

    public byte[] getTokenHash() {
        return tokenHash.clone();
    }

    public long getClaimId() {
        return claimId;
    }
}
