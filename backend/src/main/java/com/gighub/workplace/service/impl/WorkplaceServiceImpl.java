package com.gighub.workplace.service.impl;

import java.util.List;
import java.util.Objects;

import com.gighub.attendance.service.WorkplaceQrIssuer;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.api.PageRequests;
import com.gighub.common.api.PageResponse;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.common.exception.ConflictException;
import com.gighub.common.exception.ResourceNotFoundException;
import com.gighub.member.domain.UserRole;
import com.gighub.workplace.dto.WorkplaceListItemResponse;
import com.gighub.workplace.mapper.WorkplaceMapper;
import com.gighub.workplace.mapper.param.WorkplaceInsertParam;
import com.gighub.workplace.mapper.result.WorkplaceListRow;
import com.gighub.workplace.service.WorkplaceService;
import com.gighub.workplace.service.WorkplaceOwnershipService;
import com.gighub.workplace.service.result.WorkplaceLocationSnapshot;
import com.gighub.workplace.service.command.WorkplaceCreateCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** 승인된 사업장 계약을 인증 Principal과 DB 현재 상태로 적용합니다. */
@Service
@RequiredArgsConstructor
public class WorkplaceServiceImpl implements WorkplaceService, WorkplaceOwnershipService {

    private final WorkplaceMapper workplaceMapper;
    private final WorkplaceQrIssuer qrIssuer;

    @Override
    @Transactional
    public Long create(AuthPrincipal principal, WorkplaceCreateCommand command) {
        requireOwner(principal, "사업장은 OWNER만 등록할 수 있습니다.");

        WorkplaceInsertParam param = WorkplaceInsertParam.builder()
                // 소유자는 요청 Body가 아니라 인증 Principal에서만 정합니다.
                .ownerUserId(principal.getUserId())
                .businessRegistrationNumber(command.getBusinessRegistrationNumber())
                .name(command.getName())
                .representativeName(command.getRepresentativeName())
                .roadAddress(command.getRoadAddress())
                .detailAddress(command.getDetailAddress())
                .phone(command.getPhone())
                .latitude(command.getLatitude())
                .longitude(command.getLongitude())
                .build();

        insertOrReportDuplicate(param);
        // 생성 Key 회수가 깨지면 201과 함께 workplaceId=null이 조용히 나갑니다.
        // ApiResponse는 래퍼만 검사하므로 여기서 끊습니다.
        Long workplaceId = Objects.requireNonNull(param.getId(), "생성된 사업장 식별자");

        // 같은 트랜잭션에서 발급해야 QR 없는 ACTIVE 사업장이 생기지 않습니다. 조회는 QR을
        // 만들지 않으므로, 여기서 빠뜨리면 그 사업장은 재발급 전까지 QR을 얻을 수 없습니다.
        qrIssuer.issueActive(workplaceId, principal.getUserId());

        return workplaceId;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<WorkplaceListItemResponse> findOwnedWorkplaces(
            AuthPrincipal principal,
            int page,
            int size) {
        requireOwner(principal, "사업장 목록은 OWNER만 조회할 수 있습니다.");
        // 역할을 먼저 확인합니다. Page 값이 잘못된 요청이라도 권한 없는 호출자에게 400을
        // 돌려주면 Endpoint의 존재와 Query 규칙을 알려주게 됩니다.
        PageRequests.validate(page, size);

        Long ownerUserId = principal.getUserId();
        long totalElements = workplaceMapper.countByOwnerUserId(ownerUserId);
        List<WorkplaceListRow> rows = workplaceMapper.findPageByOwnerUserId(
                ownerUserId, size, PageRequests.offset(page, size));

        List<WorkplaceListItemResponse> content = rows.stream()
                .map(this::toListItemResponse)
                .toList();

        return PageResponse.of(content, page, size, totalElements);
    }

    /** Mapper Row의 저장 정밀도와 상태를 기존 공개 응답 값으로 옮깁니다. */
    private WorkplaceListItemResponse toListItemResponse(WorkplaceListRow row) {
        return WorkplaceListItemResponse.of(
                row.getWorkplaceId(),
                row.getBusinessRegistrationNumber(),
                row.getName(),
                row.getRepresentativeName(),
                row.getRoadAddress(),
                row.getDetailAddress(),
                row.getPhone(),
                row.getRadiusMeters(),
                row.getStatus());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveOwnedWorkplace(Long ownerUserId) {
        return ownerUserId != null
                && ownerUserId > 0
                && workplaceMapper.countActiveByOwnerUserId(ownerUserId) > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public void requireOwnedActiveWorkplace(Long workplaceId, Long ownerUserId) {
        if (workplaceId == null
                || ownerUserId == null
                || workplaceMapper.countOwnedActiveById(workplaceId, ownerUserId) != 1) {
            throw new ResourceNotFoundException("사업장을 찾을 수 없습니다.");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockOwnedActiveWorkplace(Long workplaceId, Long ownerUserId) {
        if (workplaceId == null
                || ownerUserId == null
                || workplaceMapper.findOwnedActiveIdForUpdate(workplaceId, ownerUserId) == null) {
            throw new ResourceNotFoundException("사업장을 찾을 수 없습니다.");
        }
    }

    /**
     * 결과가 없어도 예외로 끝내지 않습니다. 호출자인 근태 스캔은 "활성 사업장 아님"을
     * 자신의 승인된 QR 오류로 바꿔야 하는데, 여기서 사업장 조회 실패를 던지면 그 구분이
     * 사라집니다.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public WorkplaceLocationSnapshot lockActiveWorkplaceLocation(Long workplaceId) {
        if (workplaceId == null) {
            return null;
        }
        return workplaceMapper.findActiveLocationForUpdate(workplaceId);
    }

    /**
     * Security 설정은 인증 여부만 강제하므로 역할 경계는 도메인에서 확인합니다.
     *
     * <p>OWNER가 아닌 모든 인증 사용자를 거절합니다. 허용 역할을 지정하지 않고 OWNER만
     * 통과시키므로 나중에 역할이 늘어도 기본값이 거절로 유지됩니다.</p>
     *
     * <p>거절 근거가 역할 하나뿐이라 {@code 403 ROLE_MISMATCH}로 응답합니다. 소유권이나
     * 리소스 상태를 근거로 한 거절은 {@code FORBIDDEN}을 유지합니다.</p>
     */
    private void requireOwner(AuthPrincipal principal, String message) {
        if (principal.getRole() != UserRole.OWNER) {
            throw new RoleMismatchException(message);
        }
    }

    /**
     * 사업자등록번호 중복을 Unique 제약으로 판정합니다.
     *
     * <p>사전 조회 후 저장하면 두 요청이 같은 시점에 "없음"을 확인하고 모두 저장할 수
     * 있습니다. 조회와 저장 사이를 막을 수 있는 것은 DB 제약뿐이라 예외를 승인된 충돌
     * 응답으로 바꿉니다.</p>
     */
    private void insertOrReportDuplicate(WorkplaceInsertParam param) {
        try {
            workplaceMapper.insert(param);
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("이미 등록된 사업자등록번호입니다.");
        }
    }
}
