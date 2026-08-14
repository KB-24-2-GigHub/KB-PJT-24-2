package com.gighub.workplace.service.impl;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.gighub.attendance.service.WorkplaceQrIssuer;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.api.PageRequests;
import com.gighub.common.api.PageResponse;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.common.exception.ConflictException;
import com.gighub.common.exception.ResourceNotFoundException;
import com.gighub.common.exception.ValidationException;
import com.gighub.member.domain.UserRole;
import com.gighub.workplace.domain.WorkplaceCoordinateFreshness;
import com.gighub.workplace.dto.WorkplaceListItemResponse;
import com.gighub.workplace.exception.WorkplaceCoordinatesAlreadySetException;
import com.gighub.workplace.geocoding.AddressGeocoder;
import com.gighub.workplace.geocoding.GeocodedCoordinates;
import com.gighub.workplace.mapper.WorkplaceMapper;
import com.gighub.workplace.mapper.param.WorkplaceInsertParam;
import com.gighub.workplace.mapper.param.WorkplaceUpdateParam;
import com.gighub.workplace.mapper.result.WorkplaceListRow;
import com.gighub.workplace.service.WorkplaceService;
import com.gighub.workplace.service.WorkplaceOwnershipService;
import com.gighub.workplace.service.result.WorkplaceLocationSnapshot;
import com.gighub.workplace.service.command.WorkplaceCoordinateConfirmCommand;
import com.gighub.workplace.service.command.WorkplaceCreateCommand;
import com.gighub.workplace.service.command.WorkplaceUpdateCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;

/** 승인된 사업장 계약을 인증 Principal과 DB 현재 상태로 적용합니다. */
@Service
@RequiredArgsConstructor
public class WorkplaceServiceImpl implements WorkplaceService, WorkplaceOwnershipService {

    private final WorkplaceMapper workplaceMapper;
    private final WorkplaceQrIssuer qrIssuer;
    private final AddressGeocoder addressGeocoder;
    private final TransactionTemplate transactionTemplate;

    /**
     * 좌표 확정을 트랜잭션 밖에서 먼저 끝냅니다.
     *
     * <p>주소 변환은 Timeout 상한을 가진 외부 호출입니다. 트랜잭션 안에서 부르면 그 시간만큼
     * DB 커넥션을 붙잡아, 외부 서비스가 느려질 때 Pool이 사업장과 무관한 요청까지 막습니다.
     * 확정 실패는 저장이 시작되기 전에 끝나므로 사업장 행도 활성 QR도 남지 않습니다.</p>
     *
     * <p>요청이 보낸 좌표는 쓰지 않습니다 — SPEC-343-01은 좌표의 출처를 서버 주소 변환 하나로
     * 고정합니다.</p>
     */
    @Override
    public Long create(AuthPrincipal principal, WorkplaceCreateCommand command) {
        requireOwner(principal, "사업장은 OWNER만 등록할 수 있습니다.");

        GeocodedCoordinates coordinates = addressGeocoder.geocode(command.getRoadAddress());

        return transactionTemplate.execute(status -> store(principal, command, coordinates));
    }

    /** 사업장 행과 활성 QR을 한 트랜잭션에서 만듭니다. */
    private Long store(
            AuthPrincipal principal,
            WorkplaceCreateCommand command,
            GeocodedCoordinates coordinates) {
        WorkplaceInsertParam param = WorkplaceInsertParam.builder()
                // 소유자는 요청 Body가 아니라 인증 Principal에서만 정합니다.
                .ownerUserId(principal.getUserId())
                .businessRegistrationNumber(command.getBusinessRegistrationNumber())
                .name(command.getName())
                .representativeName(command.getRepresentativeName())
                .roadAddress(command.getRoadAddress())
                .detailAddress(command.getDetailAddress())
                .phone(command.getPhone())
                .latitude(coordinates.latitude())
                .longitude(coordinates.longitude())
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
                row.isAttendanceLocationConfirmed(),
                row.getStatus());
    }

    /**
     * 신선도 검증 → 소유·활성 사업장 잠금 → 좌표 상태 판정 순서를 한 트랜잭션에서 수행합니다.
     *
     * <p>신선도를 잠금보다 먼저 검사하는 이유는, 오래된 측정값은 사업장이 어떤 상태든 거절해야
     * 하는 입력 자체의 문제이기 때문입니다. 잠금부터 걸고 나중에 거절하면 불필요하게 행을
     * 붙잡습니다.</p>
     */
    @Override
    @Transactional
    public void confirmLocation(
            AuthPrincipal principal, Long workplaceId, WorkplaceCoordinateConfirmCommand command) {
        requireOwner(principal, "현장 위치 확정은 OWNER만 할 수 있습니다.");

        if (!WorkplaceCoordinateFreshness.isFresh(command.getCapturedAt(), Instant.now())) {
            throw new ValidationException("위치 측정 시각이 오래됐습니다. 위치를 다시 확인해주세요.");
        }

        WorkplaceLocationSnapshot snapshot = workplaceMapper.findOwnedActiveLocationForUpdate(
                workplaceId, principal.getUserId());
        if (snapshot == null) {
            throw new ResourceNotFoundException("사업장을 찾을 수 없습니다.");
        }

        if (snapshot.hasCoordinates()) {
            // 같은 정규화 좌표의 재요청은 응답 유실 재시도로 보아 다시 성공(204) 처리합니다.
            // 다른 값이면 이미 확정된 좌표를 보호해야 하므로 거절합니다.
            if (isSameCoordinates(snapshot, command)) {
                return;
            }
            throw new WorkplaceCoordinatesAlreadySetException(
                    "이미 다른 현장 위치가 확정된 사업장입니다.");
        }

        int confirmed = workplaceMapper.confirmCoordinates(
                workplaceId, command.getLatitude(), command.getLongitude());
        // 행을 이미 잠갔고 좌표가 비어 있음을 확인했으므로 여기서 0이 나올 수 없습니다.
        // 그래도 확인하는 이유는, 0을 그냥 흘리면 아무것도 저장하지 않고 204를 돌려주는
        // 조용한 실패가 되기 때문입니다.
        if (confirmed != 1) {
            throw new IllegalStateException(
                    "현장 위치 확정이 반영되지 않았습니다. workplaceId=" + workplaceId);
        }
    }

    /**
     * 저장된 주소를 읽어 좌표 재확정 여부를 정한 뒤, 변환을 트랜잭션 밖에서 끝내고 한 문장으로
     * 반영합니다.
     *
     * <p>변환을 트랜잭션 밖에 두는 이유는 등록 경로와 같습니다 — 외부 호출이 느려질 때 DB
     * 커넥션을 붙잡지 않기 위해서입니다. 그 결과 판단(읽기)과 반영(쓰기) 사이가 벌어지므로,
     * 좌표를 바꾸는 수정은 판단 근거였던 주소를 갱신 조건에 함께 넣습니다. 그 사이 다른 요청이
     * 주소를 바꿨다면 갱신이 0행이 되고 충돌로 보고합니다. 이 조건이 없으면 이전 주소로 구한
     * 좌표가 새 주소 위에 조용히 덮입니다.</p>
     *
     * <p>주소와 좌표를 한 UPDATE에 함께 넣어, 주소만 바뀌고 좌표가 과거 위치에 남는 중간
     * 상태를 만들지 않습니다(SPEC-349-01).</p>
     */
    @Override
    public void update(
            AuthPrincipal principal, Long workplaceId, WorkplaceUpdateCommand command) {
        requireOwner(principal, "사업장 정보는 OWNER만 수정할 수 있습니다.");

        String storedRoadAddress =
                workplaceMapper.findOwnedActiveRoadAddress(workplaceId, principal.getUserId());
        if (storedRoadAddress == null) {
            throw new ResourceNotFoundException("사업장을 찾을 수 없습니다.");
        }

        boolean recalculatesCoordinates = command.changesRoadAddressFrom(storedRoadAddress);
        // 주소가 그대로면 변환을 부르지 않습니다. 상호만 바꾸는 수정까지 외부 서비스의 장애를
        // 실패 사유로 떠안을 이유가 없습니다.
        GeocodedCoordinates coordinates =
                recalculatesCoordinates ? addressGeocoder.geocode(command.getRoadAddress()) : null;

        WorkplaceUpdateParam param = toUpdateParam(
                workplaceId,
                principal.getUserId(),
                command,
                coordinates,
                recalculatesCoordinates ? storedRoadAddress : null);

        int updated = transactionTemplate.execute(
                status -> workplaceMapper.updateOwnedActive(param));
        if (updated != 1) {
            throw new ConflictException("사업장 정보가 방금 변경됐습니다. 다시 확인해주세요.");
        }
    }

    /**
     * 좌표는 변환에 성공했을 때만 채웁니다.
     *
     * <p>{@code null} 좌표를 그대로 넘겨도 Mapper가 SET에서 빼지만, 값이 없다는 사실만으로
     * 좌표를 건드리지 않는다고 읽히면 안 되므로 여기서 명시적으로 분기합니다.</p>
     */
    private WorkplaceUpdateParam toUpdateParam(
            Long workplaceId,
            Long ownerUserId,
            WorkplaceUpdateCommand command,
            GeocodedCoordinates coordinates,
            String expectedRoadAddress) {
        return WorkplaceUpdateParam.builder()
                .workplaceId(workplaceId)
                .ownerUserId(ownerUserId)
                .nameProvided(command.isNameProvided())
                .name(command.getName())
                .roadAddressProvided(command.isRoadAddressProvided())
                .roadAddress(command.getRoadAddress())
                .detailAddressProvided(command.isDetailAddressProvided())
                .detailAddress(command.getDetailAddress())
                .phoneProvided(command.isPhoneProvided())
                .phone(command.getPhone())
                .latitude(coordinates == null ? null : coordinates.latitude())
                .longitude(coordinates == null ? null : coordinates.longitude())
                .expectedRoadAddress(expectedRoadAddress)
                .build();
    }

    /**
     * DB 저장 정밀도와 요청 값의 소수 자릿수가 다를 수 있어 {@code equals} 대신 {@code
     * compareTo}로 비교합니다.
     */
    private boolean isSameCoordinates(
            WorkplaceLocationSnapshot snapshot, WorkplaceCoordinateConfirmCommand command) {
        return snapshot.latitude().compareTo(command.getLatitude()) == 0
                && snapshot.longitude().compareTo(command.getLongitude()) == 0;
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
