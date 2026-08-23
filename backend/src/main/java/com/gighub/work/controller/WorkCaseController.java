package com.gighub.work.controller;

import java.time.LocalDate;

import javax.validation.Valid;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.auth.security.AuthPrincipals;
import com.gighub.common.api.ApiResponse;
import com.gighub.common.api.PageRequests;
import com.gighub.common.api.PageResponse;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.dto.WorkCaseCreateRequest;
import com.gighub.work.dto.WorkCaseCreateResponse;
import com.gighub.work.dto.WorkCaseDetailResponse;
import com.gighub.work.dto.WorkCaseListItemResponse;
import com.gighub.work.dto.WorkCaseSummaryResponse;
import com.gighub.work.service.WorkCaseService;
import com.gighub.work.service.command.WorkCaseCreateCommand;
import com.gighub.work.service.command.WorkCaseUpdateCommand;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OWNER 근무 {@code DRAFT} 등록·조건 수정·삭제·조회를 제공하는 Controller입니다.
 *
 * <p>Endpoint가 {@code /api/workplaces/{workplaceId}/work-cases}와
 * {@code /api/work-cases/{workCaseId}} 두 URL 베이스에 걸쳐 있어 클래스 레벨
 * {@code @RequestMapping}을 두지 않고 Method마다 전체 경로를 적습니다.</p>
 */
@RestController
public class WorkCaseController {

    private final WorkCaseService workCaseService;

    public WorkCaseController(WorkCaseService workCaseService) {
        this.workCaseService = workCaseService;
    }

    @PostMapping("/api/workplaces/{workplaceId}/work-cases")
    public ResponseEntity<ApiResponse<WorkCaseCreateResponse>> create(
            @PathVariable Long workplaceId,
            @Valid @RequestBody WorkCaseCreateRequest request,
            Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);
        Long workCaseId = workCaseService.create(principal, toCreateCommand(workplaceId, request));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(new WorkCaseCreateResponse(workCaseId)));
    }

    @ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "204", description = "근무 조건 수정 완료"))
    @PatchMapping("/api/work-cases/{workCaseId}")
    public ResponseEntity<Void> update(
            @PathVariable Long workCaseId,
            @Valid @RequestBody WorkCaseCreateRequest request,
            Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);
        workCaseService.update(principal, toUpdateCommand(workCaseId, request));

        return ResponseEntity.noContent().build();
    }

    @ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "204", description = "근무 삭제 완료"))
    @DeleteMapping("/api/work-cases/{workCaseId}")
    public ResponseEntity<Void> delete(
            @PathVariable Long workCaseId,
            Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);
        workCaseService.delete(principal, workCaseId);

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/workplaces/{workplaceId}/work-cases/summary")
    public ResponseEntity<ApiResponse<WorkCaseSummaryResponse>> summary(
            @PathVariable Long workplaceId,
            Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);

        return ResponseEntity.ok(ApiResponse.of(workCaseService.summary(principal, workplaceId)));
    }

    @GetMapping("/api/workplaces/{workplaceId}/work-cases")
    public ResponseEntity<ApiResponse<PageResponse<WorkCaseListItemResponse>>> list(
            @PathVariable Long workplaceId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) WorkCaseStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = PageRequests.DEFAULT_PAGE_TEXT) int page,
            @RequestParam(defaultValue = PageRequests.DEFAULT_SIZE_TEXT) int size,
            Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);

        return ResponseEntity.ok(ApiResponse.of(workCaseService.list(
                principal, workplaceId, keyword, status, from, to, page, size)));
    }

    @GetMapping("/api/work-cases/{workCaseId}")
    public ResponseEntity<ApiResponse<WorkCaseDetailResponse>> detail(
            @PathVariable Long workCaseId,
            Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);

        return ResponseEntity.ok(ApiResponse.of(workCaseService.detail(principal, workCaseId)));
    }

    /**
     * 검증을 통과한 요청을 Service 입력으로 옮깁니다.
     *
     * <p>요청 DTO를 그대로 넘기지 않아 Service가 HTTP·JSON 계약에 의존하지 않습니다.
     * 소유자는 여기서 옮기지 않고 Service가 인증 Principal에서 직접 채웁니다.</p>
     */
    private WorkCaseCreateCommand toCreateCommand(Long workplaceId, WorkCaseCreateRequest request) {
        return WorkCaseCreateCommand.builder()
                .workplaceId(workplaceId)
                .title(request.getTitle())
                .workDate(request.getWorkDate())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .breakMinutes(request.getBreakMinutes())
                .breakPaid(request.getBreakPaid())
                .dailyWage(request.getDailyWage())
                .build();
    }

    private WorkCaseUpdateCommand toUpdateCommand(Long workCaseId, WorkCaseCreateRequest request) {
        return WorkCaseUpdateCommand.builder()
                .workCaseId(workCaseId)
                .title(request.getTitle())
                .workDate(request.getWorkDate())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .breakMinutes(request.getBreakMinutes())
                .breakPaid(request.getBreakPaid())
                .dailyWage(request.getDailyWage())
                .build();
    }
}
