package com.gighub.work.contract;

import com.gighub.work.domain.WorkCaseStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** 정산 요청이 잠근 Work Case에서 사용하는 공개 읽기 계약입니다. */
@Getter
@Builder(toBuilder = true)
@AllArgsConstructor
public class WorkCaseEscrowSnapshot {
    private final Long workCaseId;
    private final Long employerId;
    private final Long workerId;
    private final Long agreedWage;
    private final WorkCaseStatus status;
}
