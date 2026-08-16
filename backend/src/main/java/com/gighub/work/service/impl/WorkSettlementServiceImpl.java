package com.gighub.work.service.impl;

import com.gighub.work.contract.WorkCaseEscrowSnapshot;
import com.gighub.work.mapper.WorkCaseMapper;
import com.gighub.work.service.WorkSettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Settlement가 Work persistence를 직접 다루지 않도록 의미 명령을 제공합니다. */
@Service
@RequiredArgsConstructor
public class WorkSettlementServiceImpl implements WorkSettlementService {

    private final WorkCaseMapper workCaseMapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public WorkCaseEscrowSnapshot lockEscrowContext(long workCaseId) {
        return workCaseMapper.getEscrowContextForUpdate(workCaseId);
    }

    @Override
    @Transactional(readOnly = true)
    public WorkCaseEscrowSnapshot findEscrowContext(long workCaseId) {
        return workCaseMapper.findEscrowContext(workCaseId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryLockEscrowContext(long workCaseId) {
        return workCaseMapper.lockWorkCaseIdForUpdateSkipLocked(workCaseId) != null;
    }

}
