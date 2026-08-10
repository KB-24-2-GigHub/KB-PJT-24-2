package com.gighub.work.domain;

/** Work Case 명령을 현재 저장 상태에 적용할 수 있는지 설명하는 순수 Domain 결과입니다. */
public enum WorkCaseDecision {
    ALLOWED,
    STATUS_NOT_DRAFT,
    WORKER_ALREADY_ASSIGNED,
    WORK_ALREADY_STARTED,
    TRANSITION_NOT_ALLOWED
}
