package com.gighub.work.service;

import com.gighub.work.contract.WorkCaseEscrowSnapshot;

/** Settlement outer Transaction이 사용하는 최소 Work Query/Command 경계입니다. */
public interface WorkSettlementService {

    /** Settlement보다 먼저 Work 행을 잠급니다. */
    WorkCaseEscrowSnapshot lockEscrowContext(long workCaseId);

    /** 현재 정책이 허용하는 경우 Work를 COMPLETED로 전이합니다. */
    boolean completeForPayout(WorkCaseEscrowSnapshot context);
}
