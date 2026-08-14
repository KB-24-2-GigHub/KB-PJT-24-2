package com.gighub.contract.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/** 수락 Transaction이 저장한 계약 식별자와 동일한 불변 조건을 후속 Port에 전달합니다. */
public final class AcceptedContract {

    private final long workCaseId;
    private final long contractId;
    private final LocalDateTime acceptedAt;
    private final ContractTermsSnapshot terms;
    private final LocalDateTime workCaseCreatedAt;

    private AcceptedContract(
            long workCaseId,
            long contractId,
            LocalDateTime acceptedAt,
            ContractTermsSnapshot terms,
            LocalDateTime workCaseCreatedAt) {
        this.workCaseId = workCaseId;
        this.contractId = contractId;
        this.acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
        this.terms = Objects.requireNonNull(terms, "terms");
        this.workCaseCreatedAt = Objects.requireNonNull(workCaseCreatedAt, "workCaseCreatedAt");
    }

    public static AcceptedContract of(
            long workCaseId,
            long contractId,
            LocalDateTime acceptedAt,
            ContractTermsSnapshot terms,
            LocalDateTime workCaseCreatedAt) {
        return new AcceptedContract(workCaseId, contractId, acceptedAt, terms, workCaseCreatedAt);
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

    /** 계약서 사업주란에 표시하는 근무 등록 일시입니다. 계약 조건 자체가 아니라 표시 전용입니다. */
    public LocalDateTime getWorkCaseCreatedAt() {
        return workCaseCreatedAt;
    }
}
