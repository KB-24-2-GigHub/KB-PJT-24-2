package com.gighub.work.service;

import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.service.result.WorkLifecycleSnapshot;

/** Attendance outer Transaction에 참여하는 Work 상태 전이 공개 명령입니다. */
public interface WorkLifecycleCommandService {

    /** 상태 판단과 변경 사이의 경합을 막기 위해 Work 행을 먼저 잠급니다. */
    WorkLifecycleSnapshot lock(long workCaseId);

    /** Domain 정책과 expected-state를 함께 적용합니다. */
    boolean transition(long workCaseId, WorkCaseStatus expected, WorkCaseStatus target);
}
