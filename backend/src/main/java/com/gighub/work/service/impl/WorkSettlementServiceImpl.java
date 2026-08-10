package com.gighub.work.service.impl;

import com.gighub.work.contract.WorkCaseEscrowSnapshot;
import com.gighub.work.domain.WorkCaseDecision;
import com.gighub.work.domain.WorkCasePolicy;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.mapper.WorkCaseMapper;
import com.gighub.work.service.WorkSettlementService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Settlement가 Work persistence를 직접 다루지 않도록 의미 명령을 제공합니다. */
@Service
public class WorkSettlementServiceImpl implements WorkSettlementService {

    private final WorkCaseMapper workCaseMapper;

    public WorkSettlementServiceImpl(WorkCaseMapper workCaseMapper) {
        this.workCaseMapper = workCaseMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public WorkCaseEscrowSnapshot lockEscrowContext(long workCaseId) {
        return workCaseMapper.getEscrowContextForUpdate(workCaseId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean completeForPayout(WorkCaseEscrowSnapshot context) {
        if (context.getStatus() == WorkCaseStatus.COMPLETED) {
            return true;
        }
        WorkCaseDecision decision = WorkCasePolicy.decideTransition(
                context.getStatus(), WorkCaseStatus.COMPLETED);
        return decision == WorkCaseDecision.ALLOWED
                && workCaseMapper.updateWorkStatus(
                                context.getWorkCaseId(),
                                List.of(context.getStatus()),
                                WorkCaseStatus.COMPLETED)
                        == 1;
    }
}
