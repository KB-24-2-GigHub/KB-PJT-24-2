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
    private final String employerPhone;
    private final String workerPhone;

    private AcceptedContract(
            long workCaseId,
            long contractId,
            LocalDateTime acceptedAt,
            ContractTermsSnapshot terms,
            LocalDateTime workCaseCreatedAt,
            String employerPhone,
            String workerPhone) {
        this.workCaseId = workCaseId;
        this.contractId = contractId;
        this.acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
        this.terms = Objects.requireNonNull(terms, "terms");
        this.workCaseCreatedAt = Objects.requireNonNull(workCaseCreatedAt, "workCaseCreatedAt");
        this.employerPhone = employerPhone;
        this.workerPhone = workerPhone;
    }

    public static AcceptedContract of(
            long workCaseId,
            long contractId,
            LocalDateTime acceptedAt,
            ContractTermsSnapshot terms,
            LocalDateTime workCaseCreatedAt,
            String employerPhone,
            String workerPhone) {
        return new AcceptedContract(
                workCaseId, contractId, acceptedAt, terms, workCaseCreatedAt,
                employerPhone, workerPhone);
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

    /** 계약서 표시 전용 사업주 연락처입니다. {@code users.phone}이 선택 입력이라 비어 있을 수 있습니다. */
    public String getEmployerPhone() {
        return employerPhone;
    }

    /** 계약서 표시 전용 근로자 연락처입니다. {@code users.phone}이 선택 입력이라 비어 있을 수 있습니다. */
    public String getWorkerPhone() {
        return workerPhone;
    }
}
