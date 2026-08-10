package com.gighub.contract.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/** 수락 Transaction이 저장한 계약 식별자와 동일한 불변 조건을 후속 Port에 전달합니다. */
public final class AcceptedContract {

    private final long workCaseId;
    private final long contractId;
    private final LocalDateTime acceptedAt;
    private final ContractTermsSnapshot terms;

    private AcceptedContract(
            long workCaseId,
            long contractId,
            LocalDateTime acceptedAt,
            ContractTermsSnapshot terms) {
        this.workCaseId = workCaseId;
        this.contractId = contractId;
        this.acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
        this.terms = Objects.requireNonNull(terms, "terms");
    }

    public static AcceptedContract of(
            long workCaseId,
            long contractId,
            LocalDateTime acceptedAt,
            ContractTermsSnapshot terms) {
        return new AcceptedContract(workCaseId, contractId, acceptedAt, terms);
    }

    public long getWorkCaseId() {
        return workCaseId;
    }

    public long getContractId() {
        return contractId;
    }

    public LocalDateTime getAcceptedAt() {
        return acceptedAt;
    }

    public ContractTermsSnapshot getTerms() {
        return terms;
    }
}
