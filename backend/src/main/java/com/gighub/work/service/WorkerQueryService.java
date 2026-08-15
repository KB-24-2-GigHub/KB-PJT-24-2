package com.gighub.work.service;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.api.PageResponse;
import com.gighub.work.dto.ShareableWorkplaceListItemResponse;
import com.gighub.work.dto.WorkerHomeResponse;
import com.gighub.work.dto.WorkerWorkCaseListItemResponse;

/** 인증 WORKER 본인의 홈 요약과 근무 이력 읽기 전용 조회입니다. */
public interface WorkerQueryService {

    WorkerHomeResponse home(AuthPrincipal principal);

    PageResponse<WorkerWorkCaseListItemResponse> workCases(AuthPrincipal principal, int page, int size);

    /**
     * 보건증을 새로 공유할 수 있는 근무 관계를 조회합니다.
     *
     * <p>{@code documentId}를 받지 않으므로 특정 보건증의 만료나 중복 공유는 판정하지
     * 않습니다. 공유 요청이 그 검증을 다시 수행합니다.</p>
     */
    PageResponse<ShareableWorkplaceListItemResponse> shareableWorkplaces(
            AuthPrincipal principal, int page, int size);
}
