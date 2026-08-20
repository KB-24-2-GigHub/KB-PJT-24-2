package com.gighub.work.service.impl;

import com.gighub.work.mapper.WorkCaseMapper;
import com.gighub.work.service.WorkParticipationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 근무 참여 여부 질의를 work 모듈 안에서 처리합니다. */
@Service
@RequiredArgsConstructor
public class WorkParticipationQueryServiceImpl implements WorkParticipationQueryService {

    private final WorkCaseMapper workCaseMapper;

    @Override
    @Transactional(readOnly = true)
    public int countUnfinished(Long userId) {
        return workCaseMapper.countUnfinishedByParticipant(userId);
    }
}
