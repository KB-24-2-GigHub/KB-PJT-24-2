package com.gighub.work.service;

import com.gighub.work.contract.WorkCaseEscrowSnapshot;

/** Settlement outer Transaction이 사용하는 최소 Work Query/Command 경계입니다. */
public interface WorkSettlementService {

    /** Settlement보다 먼저 Work 행을 잠급니다. */
    WorkCaseEscrowSnapshot lockEscrowContext(long workCaseId);

    /**
     * #172 Scheduler 전용 SKIP LOCKED 선점. 수동 승인·다른 Scheduler 인스턴스가 이미 이 Work
     * Case를 잠그고 있으면 대기하지 않고 {@code false}를 반환한다.
     *
     * @return 잠금에 성공했으면 {@code true}
     */
    boolean tryLockEscrowContext(long workCaseId);
}
