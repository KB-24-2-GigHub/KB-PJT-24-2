package com.gighub.work.controller;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.auth.security.AuthPrincipals;
import com.gighub.common.api.ApiResponse;
import com.gighub.common.api.PageRequests;
import com.gighub.common.api.PageResponse;
import com.gighub.work.dto.WorkerHomeResponse;
import com.gighub.work.dto.WorkerWorkCaseListItemResponse;
import com.gighub.work.service.WorkerQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** WORKER 홈 요약과 본인 근무 이력을 제공하는 Controller입니다. */
@RestController
public class WorkerController {

    private final WorkerQueryService workerQueryService;

    public WorkerController(WorkerQueryService workerQueryService) {
        this.workerQueryService = workerQueryService;
    }

    @GetMapping("/api/worker/home")
    public ResponseEntity<ApiResponse<WorkerHomeResponse>> home(Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);

        return ResponseEntity.ok(ApiResponse.of(workerQueryService.home(principal)));
    }

    @GetMapping("/api/worker/work-cases")
    public ResponseEntity<ApiResponse<PageResponse<WorkerWorkCaseListItemResponse>>> workCases(
            @RequestParam(defaultValue = PageRequests.DEFAULT_PAGE_TEXT) int page,
            @RequestParam(defaultValue = PageRequests.DEFAULT_SIZE_TEXT) int size,
            Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);

        return ResponseEntity.ok(ApiResponse.of(workerQueryService.workCases(principal, page, size)));
    }
}
