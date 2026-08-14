package com.gighub.workplace.controller;

import javax.validation.Valid;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.auth.security.AuthPrincipals;
import com.gighub.common.api.ApiResponse;
import com.gighub.common.api.PageRequests;
import com.gighub.common.api.PageResponse;
import com.gighub.workplace.dto.WorkplaceCoordinateConfirmRequest;
import com.gighub.workplace.dto.WorkplaceCreateRequest;
import com.gighub.workplace.dto.WorkplaceCreateResponse;
import com.gighub.workplace.dto.WorkplaceListItemResponse;
import com.gighub.workplace.dto.WorkplaceUpdateRequest;
import com.gighub.workplace.service.WorkplaceService;
import com.gighub.workplace.service.command.WorkplaceCoordinateConfirmCommand;
import com.gighub.workplace.service.command.WorkplaceCreateCommand;
import com.gighub.workplace.service.command.WorkplaceUpdateCommand;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 사업장 등록과 소유 사업장 조회를 제공하는 Controller입니다. */
@RestController
@RequestMapping("/api/workplaces")
public class WorkplaceController {

    private final WorkplaceService workplaceService;

    public WorkplaceController(WorkplaceService workplaceService) {
        this.workplaceService = workplaceService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<WorkplaceCreateResponse>> create(
            @Valid @RequestBody WorkplaceCreateRequest request,
            Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);
        Long workplaceId = workplaceService.create(principal, toCommand(request));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(new WorkplaceCreateResponse(workplaceId)));
    }

    /**
     * 인증 OWNER가 소유한 사업장 목록을 승인된 Page Envelope로 반환합니다.
     *
     * <p>Query 기본값은 공통 상수를 씁니다. Endpoint마다 숫자를 직접 적으면 허용 범위가
     * 갈라지므로 값과 검증을 {@link PageRequests} 한 곳에만 둡니다. 경계 검증은 소유권·역할
     * 판단과 함께 Service가 수행합니다.</p>
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<WorkplaceListItemResponse>>> findOwned(
            @RequestParam(defaultValue = PageRequests.DEFAULT_PAGE_TEXT) int page,
            @RequestParam(defaultValue = PageRequests.DEFAULT_SIZE_TEXT) int size,
            Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);

        return ResponseEntity.ok(
                ApiResponse.of(workplaceService.findOwnedWorkplaces(principal, page, size)));
    }

    /**
     * 좌표가 비어 있는 소유 사업장의 현장 위치를 한 번 확정합니다.
     *
     * <p>성공은 본문 없는 204입니다. 새로 확정됐든 같은 좌표의 재시도든 호출자 입장에서는
     * 구분할 필요가 없는 같은 성공이므로 Service가 반환값으로 구분하지 않습니다.</p>
     */
    @PutMapping("/{workplaceId}/coordinates")
    public ResponseEntity<Void> confirmLocation(
            @PathVariable Long workplaceId,
            @Valid @RequestBody WorkplaceCoordinateConfirmRequest request,
            Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);
        workplaceService.confirmLocation(principal, workplaceId, toCommand(request));

        return ResponseEntity.noContent().build();
    }

    /**
     * 소유 사업장의 수정 가능한 필드를 부분 수정합니다.
     *
     * <p>도로명주소가 바뀌면 서버가 좌표를 다시 확정합니다(SPEC-349-01). 성공은 본문 없는
     * 204입니다 — 좌표는 응답에 싣지 않는 값이라 돌려줄 표현이 없습니다.</p>
     */
    @PatchMapping("/{workplaceId}")
    public ResponseEntity<Void> update(
            @PathVariable Long workplaceId,
            @Valid @RequestBody WorkplaceUpdateRequest request,
            Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);
        workplaceService.update(principal, workplaceId, toCommand(request));

        return ResponseEntity.noContent().build();
    }

    /**
     * 검증을 통과한 요청을 Service 입력으로 옮깁니다.
     *
     * <p>요청 DTO를 그대로 넘기지 않아 Service가 HTTP·JSON 계약에 의존하지 않습니다.
     * 소유자는 여기서 옮기지 않고 Service가 인증 Principal에서 직접 채웁니다.</p>
     */
    private WorkplaceCreateCommand toCommand(WorkplaceCreateRequest request) {
        return WorkplaceCreateCommand.builder()
                .businessRegistrationNumber(request.getBusinessRegistrationNumber())
                .name(request.getName())
                .representativeName(request.getRepresentativeName())
                .roadAddress(request.getRoadAddress())
                .detailAddress(request.getDetailAddress())
                .phone(request.getPhone())
                .build();
    }

    private WorkplaceCoordinateConfirmCommand toCommand(WorkplaceCoordinateConfirmRequest request) {
        return WorkplaceCoordinateConfirmCommand.builder()
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .capturedAt(request.getCapturedAt())
                .build();
    }

    /**
     * 값과 함께 "요청에 그 필드가 있었는지"를 옮깁니다.
     *
     * <p>값만 옮기면 상세주소를 지우는 요청과 건드리지 않는 요청이 Service에서 같은 입력이
     * 됩니다.</p>
     */
    private WorkplaceUpdateCommand toCommand(WorkplaceUpdateRequest request) {
        return WorkplaceUpdateCommand.builder()
                .nameProvided(request.isNameProvided())
                .name(request.getName())
                .roadAddressProvided(request.isRoadAddressProvided())
                .roadAddress(request.getRoadAddress())
                .detailAddressProvided(request.isDetailAddressProvided())
                .detailAddress(request.getDetailAddress())
                .phoneProvided(request.isPhoneProvided())
                .phone(request.getPhone())
                .build();
    }
}
