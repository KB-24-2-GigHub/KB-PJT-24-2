package com.gighub.work.service.impl;

import com.gighub.work.domain.WorkCaseDecision;
import com.gighub.work.domain.WorkCasePolicy;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.mapper.WorkCaseMapper;
import com.gighub.work.mapper.result.WorkCaseLockRow;
import com.gighub.work.service.WorkLifecycleCommandService;
import com.gighub.work.service.result.WorkLifecycleSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Work 상태 정책과 단일 owner Mapper를 함께 캡슐화합니다. */
@Service
@RequiredArgsConstructor
public class WorkLifecycleCommandServiceImpl implements WorkLifecycleCommandService {

    private final WorkCaseMapper workCaseMapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public WorkLifecycleSnapshot lock(long workCaseId) {
        WorkCaseLockRow row = workCaseMapper.lockById(workCaseId);
        if (row == null) {
            return null;
        }
        return new WorkLifecycleSnapshot(
                row.getWorkCaseId(),
                row.getStatus(),
                row.getStartsAt(),
                row.getEndsAt(),
                row.getAgreedWage(),
                row.getBreakMinutes(),
                row.getBreakPaid());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean transition(
            long workCaseId, WorkCaseStatus expected, WorkCaseStatus target) {
        if (WorkCasePolicy.decideTransition(expected, target) != WorkCaseDecision.ALLOWED) {
            return false;
        }
        return workCaseMapper.updateWorkStatus(
                workCaseId, List.of(expected), target) == 1;
    }
}
