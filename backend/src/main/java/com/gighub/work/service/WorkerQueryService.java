package com.gighub.work.service;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.api.PageResponse;
import com.gighub.work.dto.WorkerHomeResponse;
import com.gighub.work.dto.WorkerWorkCaseListItemResponse;

/** 인증 WORKER 본인의 홈 요약과 근무 이력 읽기 전용 조회입니다. */
public interface WorkerQueryService {

    WorkerHomeResponse home(AuthPrincipal principal);

    PageResponse<WorkerWorkCaseListItemResponse> workCases(AuthPrincipal principal, int page, int size);
}
