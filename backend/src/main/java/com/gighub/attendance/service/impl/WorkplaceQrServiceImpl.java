package com.gighub.attendance.service.impl;

import java.time.Instant;

import com.gighub.attendance.dto.WorkplaceQrReissueResponse;
import com.gighub.attendance.dto.WorkplaceQrResponse;
import com.gighub.attendance.exception.WorkplaceQrIntegrityException;
import com.gighub.attendance.mapper.QrTokenMapper;
import com.gighub.attendance.mapper.result.QrTokenRow;
import com.gighub.attendance.qr.QrTokenCodec;
import com.gighub.attendance.service.WorkplaceQrIssuer;
import com.gighub.attendance.service.WorkplaceQrService;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.api.ApiTimes;
import com.gighub.common.exception.ConflictException;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.member.domain.UserRole;
import com.gighub.workplace.service.WorkplaceOwnershipService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 사업장 고정 QR 계약을 인증 Principal과 DB 현재 상태로 적용합니다. */
@Service
public class WorkplaceQrServiceImpl implements WorkplaceQrService {

    private final WorkplaceOwnershipService workplaceOwnershipService;
    private final QrTokenMapper qrTokenMapper;
    private final QrTokenCodec qrTokenCodec;
    private final WorkplaceQrIssuer qrIssuer;

    public WorkplaceQrServiceImpl(
            WorkplaceOwnershipService workplaceOwnershipService,
            QrTokenMapper qrTokenMapper,
            QrTokenCodec qrTokenCodec,
            WorkplaceQrIssuer qrIssuer) {
        this.workplaceOwnershipService = workplaceOwnershipService;
        this.qrTokenMapper = qrTokenMapper;
        this.qrTokenCodec = qrTokenCodec;
        this.qrIssuer = qrIssuer;
    }

    @Override
    @Transactional(readOnly = true)
    public WorkplaceQrResponse findQr(AuthPrincipal principal, Long workplaceId) {
        requireOwnedWorkplace(principal, workplaceId);

        QrTokenRow row = qrTokenMapper.findActiveByWorkplaceId(workplaceId);
        if (row == null) {
            // 조회는 QR을 만들지 않습니다. 여기서 조용히 발급하면 읽기 요청이 쓰기가 되고,
            // 애초에 발급이 누락된 사실도 드러나지 않습니다.
            throw new WorkplaceQrIntegrityException(
                    "ACTIVE 사업장 " + workplaceId + "에 활성 고정 QR이 없습니다.");
        }

        return new WorkplaceQrResponse(
                workplaceId,
                qrTokenCodec.sign(workplaceId, row.getTokenNonce()),
                ApiTimes.toInstant(row.getCreatedAt()));
    }

    @Override
    @Transactional
    public WorkplaceQrReissueResponse reissue(AuthPrincipal principal, Long workplaceId) {
        requireOwnerRole(principal);
        // 사업장을 먼저 잠급니다. 잠금 순서는 workplaces -> qr_tokens로 고정합니다.
        workplaceOwnershipService.lockOwnedActiveWorkplace(
                workplaceId, principal.getUserId());

        // 활성 QR이 없어도 0을 받고 그대로 진행합니다. 발급 누락 상태를 여기서 복구합니다.
        qrTokenMapper.revokeActiveByWorkplaceId(workplaceId);
        byte[] nonce = issueOrReportConflict(workplaceId, principal.getUserId());

        return new WorkplaceQrReissueResponse(
                workplaceId,
                qrTokenCodec.sign(workplaceId, nonce),
                Instant.now());
    }

    /**
     * 활성 QR 유일성 위반을 승인된 충돌 응답으로 바꿉니다.
     *
     * <p>행 잠금을 잡았더라도 최종 보장은 {@code uk_qr_tokens_workplace_active}입니다. 잠금
     * 범위 밖에서 들어온 경로가 생기면 이 예외만이 두 활성 QR을 막습니다.</p>
     */
    private byte[] issueOrReportConflict(Long workplaceId, Long ownerUserId) {
        try {
            return qrIssuer.issueActive(workplaceId, ownerUserId);
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("QR 재발급이 이미 처리 중입니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    /**
     * 역할을 먼저 확인합니다.
     *
     * <p>역할이 다른 호출자에게 소유권 판단 결과를 돌려주면 사업장 식별자의 존재 여부를
     * 알려주게 됩니다.</p>
     */
    private void requireOwnerRole(AuthPrincipal principal) {
        if (principal.getRole() != UserRole.OWNER) {
            throw new RoleMismatchException("사업장 QR은 OWNER만 사용할 수 있습니다.");
        }
    }

    /**
     * 역할과 소유권을 확인합니다.
     *
     * <p>없는 사업장과 남의 사업장을 구분하지 않습니다. 구분하면 비소유자가 식별자의 존재를
     * 알아낼 수 있습니다.</p>
     */
    private void requireOwnedWorkplace(AuthPrincipal principal, Long workplaceId) {
        requireOwnerRole(principal);
        workplaceOwnershipService.requireOwnedActiveWorkplace(
                workplaceId, principal.getUserId());
    }
}
