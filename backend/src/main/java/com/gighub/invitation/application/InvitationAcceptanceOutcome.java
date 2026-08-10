package com.gighub.invitation.application;

import com.gighub.contract.ContractArtifactHandle;

import java.util.Objects;

/** Commit된 수락 결과와 commit 뒤 승격할 계약 Artifact를 함께 전달합니다. */
public final class InvitationAcceptanceOutcome {

    private final InvitationAcceptanceResult result;
    private final ContractArtifactHandle artifact;

    public InvitationAcceptanceOutcome(
            InvitationAcceptanceResult result, ContractArtifactHandle artifact) {
        this.result = Objects.requireNonNull(result, "result");
        this.artifact = Objects.requireNonNull(artifact, "artifact");
    }

    public InvitationAcceptanceResult getResult() {
        return result;
    }

    public ContractArtifactHandle getArtifact() {
        return artifact;
    }
}
