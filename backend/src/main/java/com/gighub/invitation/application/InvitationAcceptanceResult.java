package com.gighub.invitation.application;

/** API DTO와 분리된 초대 수락 Application 결과입니다. */
public final class InvitationAcceptanceResult {

    private static final String HELD = "HELD";

    private final long workCaseId;
    private final String escrowStatus;

    private InvitationAcceptanceResult(long workCaseId, String escrowStatus) {
        this.workCaseId = workCaseId;
        this.escrowStatus = escrowStatus;
    }

    public static InvitationAcceptanceResult held(long workCaseId) {
        return new InvitationAcceptanceResult(workCaseId, HELD);
    }

    public static InvitationAcceptanceResult of(long workCaseId, String escrowStatus) {
        if (!HELD.equals(escrowStatus)) {
            throw new IllegalStateException("저장된 수락 결과의 예치 상태가 HELD가 아닙니다.");
        }
        return held(workCaseId);
    }

    public long getWorkCaseId() {
        return workCaseId;
    }

    public String getEscrowStatus() {
        return escrowStatus;
    }
}
