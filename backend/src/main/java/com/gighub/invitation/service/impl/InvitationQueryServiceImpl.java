package com.gighub.invitation.service.impl;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.badge.service.BadgeApplicationService;
import com.gighub.badge.service.result.BadgeCalculationResult;
import com.gighub.common.api.ApiTimes;
import com.gighub.common.exception.ConflictException;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.invitation.domain.InvitationDecision;
import com.gighub.invitation.domain.InvitationPolicy;
import com.gighub.invitation.dto.InvitationDetailResponse;
import com.gighub.invitation.dto.OwnerBadgeResponse;
import com.gighub.invitation.exception.InvitationAlreadyAcceptedException;
import com.gighub.invitation.exception.InvitationExpiredException;
import com.gighub.invitation.exception.InvitationNotFoundException;
import com.gighub.invitation.exception.InvitationRevokedException;
import com.gighub.invitation.exception.InvitationTermsChangedException;
import com.gighub.invitation.mapper.InvitationMapper;
import com.gighub.invitation.mapper.result.InvitationRow;
import com.gighub.invitation.mapper.result.InvitationWorkCaseRow;
import com.gighub.invitation.service.InvitationQueryService;
import com.gighub.invitation.token.InvitationTokenCodec;
import com.gighub.member.domain.UserRole;
import com.gighub.work.domain.WorkCaseDecision;
import com.gighub.work.domain.WorkCasePolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 초대 Token의 상태와 근무 조건 Version을 확인하고 승인된 조건만 돌려줍니다.
 *
 * <p>검증 순서가 계약의 일부입니다. 역할을 먼저 보고, 그다음 Token 형식과 존재를 하나의
 * 결과로 처리한 뒤, 상태와 조건 Version을 확인합니다. 순서가 바뀌면 인증만 한 사람이 응답
 * 차이를 통해 어떤 Token이 실재하는지 알아낼 수 있습니다.</p>
 */
@Service
public class InvitationQueryServiceImpl implements InvitationQueryService {

    /** DB의 DATETIME은 Asia/Seoul 벽시계 값이므로 비교 기준 시각도 같은 지역으로 만듭니다. */
    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Seoul");

    private static final String UNUSABLE_INVITATION = "초대 상태를 다시 확인해 주세요.";

    private final InvitationMapper invitationMapper;
    private final InvitationTokenCodec tokenCodec;
    private final BadgeApplicationService badgeApplicationService;
    private final Clock clock;

    @Autowired
    public InvitationQueryServiceImpl(
            InvitationMapper invitationMapper,
            InvitationTokenCodec tokenCodec,
            BadgeApplicationService badgeApplicationService) {
        this(invitationMapper, tokenCodec, badgeApplicationService, Clock.system(DATABASE_ZONE));
    }

    /** 만료 경계를 검증할 때만 고정 Clock을 주입합니다. */
    InvitationQueryServiceImpl(
            InvitationMapper invitationMapper,
            InvitationTokenCodec tokenCodec,
            BadgeApplicationService badgeApplicationService,
            Clock clock) {
        this.invitationMapper = invitationMapper;
        this.tokenCodec = tokenCodec;
        this.badgeApplicationService = badgeApplicationService;
        this.clock = clock;
    }

    /**
     * 만료 전이는 410 응답과 함께 확정되어야 하므로 이 예외에서는 Rollback하지 않습니다.
     *
     * <p>{@code InvitationExpiredException}도 RuntimeException이라 기본 규칙대로면 앞서 기록한
     * {@code EXPIRED} 전이가 함께 지워집니다. 그러면 만료된 초대가 {@code PENDING}으로 남아
     * 활성 초대 Unique 제약이 OWNER의 새 발급을 계속 막습니다. 이 경로에서 쓰는 값은 그
     * 전이 하나뿐이라 보존해도 다른 변경이 함께 남지 않습니다.</p>
     */
    @Override
    @Transactional(noRollbackFor = InvitationExpiredException.class)
    public InvitationDetailResponse findByToken(AuthPrincipal principal, String token) {
        // 초대는 WORKER가 수락하는 흐름이므로 다른 역할에는 존재 여부조차 알리지 않습니다.
        if (principal.getRole() != UserRole.WORKER) {
            throw new RoleMismatchException("초대는 WORKER만 조회할 수 있습니다.");
        }

        // 형식이 다른 값은 저장소를 조회하지 않고 미존재와 같은 결과로 끝냅니다.
        if (!tokenCodec.isWellFormed(token)) {
            throw new InvitationNotFoundException();
        }

        InvitationRow invitation =
                invitationMapper.findByTokenHashForUpdate(tokenCodec.hash(token));
        if (invitation == null) {
            throw new InvitationNotFoundException();
        }

        requireUsableInvitation(invitation);

        InvitationWorkCaseRow workCase = Objects.requireNonNull(
                invitationMapper.findWorkCaseForInvitation(invitation.getWorkCaseId()),
                "초대가 가리키는 근무"
        );
        requireUnchangedTerms(invitation, workCase);
        requireAcceptableWorkCase(workCase);

        return InvitationDetailResponse.of(
                workCase.getTitle(),
                workCase.getWorkplaceName(),
                ApiTimes.toInstant(workCase.getStartsAt()),
                ApiTimes.toInstant(workCase.getEndsAt()),
                workCase.getBreakMinutes(),
                workCase.getBreakPaid(),
                workCase.getDailyWage(),
                workCase.getTermsVersion(),
                ApiTimes.toInstant(invitation.getExpiresAt()),
                ownerBadge(workCase.getEmployerId())
        );
    }

    /**
     * 초대를 발급한 OWNER의 같은 산정 결과를 재사용합니다.
     *
     * <p>Badge Application 경계가 사용자 행을 잠그고 재계산·Upsert까지 마친 뒤 돌려준
     * 결과이며, 0단계는 활성 Badge 없음과 같은 {@code null}로 응답한다는 기존 계약을
     * 유지합니다.</p>
     */
    private OwnerBadgeResponse ownerBadge(Long employerId) {
        BadgeCalculationResult result = badgeApplicationService.recalculate(employerId);
        if (result.getLevel() <= 0) {
            return null;
        }
        return OwnerBadgeResponse.of(result.getBadgeType(), result.getLevel());
    }

    /**
     * 종료된 초대는 상태별로 다른 승인 오류를 냅니다.
     *
     * <p>어떤 경우에도 근무 조건은 응답에 담기지 않으므로, 상태를 구분해도 초대 내용이
     * 새지 않습니다.</p>
     */
    private void requireUsableInvitation(InvitationRow invitation) {
        InvitationDecision decision = InvitationPolicy.decideUse(
                invitation.getStatus(), invitation.getExpiresAt(), LocalDateTime.now(clock));
        switch (decision) {
            case USABLE -> {
                return;
            }
            case ALREADY_ACCEPTED -> throw new InvitationAlreadyAcceptedException();
            case REVOKED -> throw new InvitationRevokedException();
            case EXPIRED -> throw new InvitationExpiredException();
            case EXPIRE_NOW -> {
                // 410과 함께 전이를 보존해 활성 PENDING Slot을 해제합니다.
                if (invitationMapper.markExpired(invitation.getId()) != 1) {
                    throw new IllegalStateException("잠근 PENDING 초대를 만료시키지 못했습니다.");
                }
                throw new InvitationExpiredException();
            }
            case TERMS_CHANGED, UNSUPPORTED_STATUS ->
                    throw new ConflictException(UNUSABLE_INVITATION);
        }
    }

    /**
     * 초대가 기대한 조건 Version과 현재 조건이 같아야 합니다.
     *
     * <p>다르면 이전 Snapshot을 보여 주지 않고 끊습니다. 지난 조건으로 근무를 확정하면
     * 실제 계약 내용과 화면이 어긋납니다.</p>
     */
    private void requireUnchangedTerms(InvitationRow invitation, InvitationWorkCaseRow workCase) {
        InvitationDecision decision = InvitationPolicy.decideTerms(
                invitation.getExpectedTermsVersion(), workCase.getTermsVersion());
        if (decision == InvitationDecision.TERMS_CHANGED) {
            throw new InvitationTermsChangedException();
        }
    }

    /** 이미 당사자가 정해졌거나 확정할 수 없는 상태의 근무는 조건을 보여 주지 않습니다. */
    private void requireAcceptableWorkCase(InvitationWorkCaseRow workCase) {
        WorkCaseDecision decision = WorkCasePolicy.decideInvitationAccept(
                workCase.getStatus(),
                workCase.getWorkerId(),
                workCase.getStartsAt(),
                LocalDateTime.now(clock));
        if (decision == WorkCaseDecision.WORKER_ALREADY_ASSIGNED) {
            throw new InvitationAlreadyAcceptedException();
        }
        if (decision != WorkCaseDecision.ALLOWED) {
            throw new ConflictException(UNUSABLE_INVITATION);
        }
    }
}
