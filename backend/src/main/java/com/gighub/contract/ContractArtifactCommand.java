package com.gighub.contract;

import com.gighub.contract.domain.AcceptedContract;
import com.gighub.contract.domain.ContractTermsSnapshot;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 계약서 파일을 만들기 위해 수락 Aggregate가 넘기는 최소 정보입니다.
 *
 * <p>계약 INSERT와 문서 생성은 수락 Transaction의 같은 {@link AcceptedContract} 결과를
 * 사용합니다. Document Adapter가 Contract Mapper를 다시 호출하지 않으므로, 두 모듈이 서로
 * 다른 Snapshot 조립 규칙을 소유하지 않습니다.</p>
 */
public final class ContractArtifactCommand {

    private final long workCaseId;
    private final long contractId;
    private final LocalDateTime acceptedAt;
    private final ContractTermsSnapshot terms;
    private final LocalDateTime workCaseCreatedAt;

    private ContractArtifactCommand(AcceptedContract contract) {
        Objects.requireNonNull(contract, "contract");
        this.workCaseId = contract.getWorkCaseId();
        this.contractId = contract.getContractId();
        this.acceptedAt = contract.getAcceptedAt();
        this.terms = contract.getTerms();
        this.workCaseCreatedAt = contract.getWorkCaseCreatedAt();
    }

    public static ContractArtifactCommand from(AcceptedContract contract) {
        return new ContractArtifactCommand(contract);
    }

    public long getWorkCaseId() {
        return workCaseId;
    }

    public long getContractId() {
        return contractId;
    }

    /** Aggregate 전체가 공유하는 수락 시각입니다. 문서·서명 시각도 이 값을 씁니다. */
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
