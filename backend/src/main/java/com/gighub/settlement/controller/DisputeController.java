package com.gighub.settlement.controller;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.auth.security.AuthPrincipals;
import com.gighub.common.api.ApiResponse;
import com.gighub.common.api.PageRequests;
import com.gighub.common.api.PageResponse;
import com.gighub.settlement.dto.DisputeCreateRequest;
import com.gighub.settlement.dto.DisputeCreateResponse;
import com.gighub.settlement.dto.DisputeListItemResponse;
import com.gighub.settlement.service.DisputeService;
import com.gighub.settlement.service.command.DisputeCreateCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/** 근무 당사자의 임금분쟁 생성과 Page 조회 API입니다. */
@RestController
@RequiredArgsConstructor
public class DisputeController {

    private final DisputeService disputeService;

    @PostMapping("/api/work-cases/{workCaseId}/disputes")
    public ResponseEntity<ApiResponse<DisputeCreateResponse>> create(
            @PathVariable Long workCaseId,
            @Valid @RequestBody DisputeCreateRequest request,
            Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);
        Long reportId = disputeService.create(DisputeCreateCommand.builder()
                .workCaseId(workCaseId)
                .requesterUserId(principal.getUserId())
                .requesterRole(principal.getRole())
                .title(request.getTitle())
                .content(request.getContent())
                .build());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(new DisputeCreateResponse(reportId)));
    }

    @GetMapping("/api/work-cases/{workCaseId}/disputes")
    public ApiResponse<PageResponse<DisputeListItemResponse>> findPage(
            @PathVariable Long workCaseId,
            @RequestParam(defaultValue = PageRequests.DEFAULT_PAGE_TEXT) int page,
            @RequestParam(defaultValue = PageRequests.DEFAULT_SIZE_TEXT) int size,
            Authentication authentication) {
        PageRequests.validate(page, size);
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);
        return ApiResponse.of(disputeService.findPage(
                workCaseId,
                principal.getUserId(),
                principal.getRole(),
                page,
                size
        ));
    }
}
