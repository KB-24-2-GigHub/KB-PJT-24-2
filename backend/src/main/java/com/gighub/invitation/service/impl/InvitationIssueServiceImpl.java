package com.gighub.invitation.service.impl;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.badge.service.BadgeApplicationService;
import com.gighub.common.api.ApiTimes;
import com.gighub.common.exception.ConflictException;
import com.gighub.common.exception.ResourceNotFoundException;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.common.exception.WorkCaseLockedException;
import com.gighub.invitation.config.InvitationLinkFactory;
import com.gighub.work.domain.WorkCaseDecision;
import com.gighub.work.domain.WorkCasePolicy;
import com.gighub.invitation.dto.InvitationIssueResponse;
import com.gighub.invitation.mapper.InvitationMapper;
import com.gighub.invitation.mapper.param.InvitationInsertParam;
import com.gighub.invitation.mapper.result.InvitationRow;
import com.gighub.invitation.mapper.result.InvitationWorkCaseLockRow;
import com.gighub.invitation.service.InvitationIssueResult;
import com.gighub.invitation.service.InvitationIssueService;
import com.gighub.invitation.token.InvitationTokenCodec;
import com.gighub.member.domain.UserRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 근무 조건을 잠근 상태에서 활성 초대 하나만 유지하며 Link를 발급합니다.
 *
 * <p>잠금 순서는 <b>근무 행 → 초대 행</b>입니다. 근무 조건 수정 흐름도 근무 행을 먼저
 * 잠그므로 두 흐름이 동시에 실행돼도 교착이 생기지 않습니다. 근무 행을 잡고 있는 동안에는
 * 같은 근무의 다른 발급 요청이 대기하므로, 동시 발급도 순서대로 처리됩니다.</p>
 *
 * <p>새 초대를 만들 때 발급하는 OWNER의 배지를 한 번 재계산해 둡니다. 초대를 여는 WORKER
 * 쪽(조회)은 이 값을 잠금 없이 읽기만 하므로, 배지는 "초대를 발급한 시점" 기준으로 고정되어
 * 보입니다. OWNER 한 명이 초대를 발급하는 동작은 이미 근무 행 잠금으로 순차 처리되어 있어
 * 여기서 재계산해도 새로운 동시성 경합이 생기지 않습니다.</p>
 */
@Service
public class InvitationIssueServiceImpl implements InvitationIssueService {

    /** DB의 DATETIME은 Asia/Seoul 벽시계 값이므로 비교 기준 시각도 같은 지역으로 만듭니다. */
    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Seoul");

    private static final String NOT_ISSUABLE = "초대를 발급할 수 없는 근무입니다.";
    private static final String UNUSABLE_INVITATION = "초대 상태를 다시 확인해 주세요.";
    private static final String WORK_CASE_NOT_FOUND = "근무 Case를 찾을 수 없습니다.";

    private final InvitationMapper invitationMapper;
    private final InvitationTokenCodec tokenCodec;
    private final InvitationLinkFactory linkFactory;
    private final BadgeApplicationService badgeApplicationService;
    private final Clock clock;

    @Autowired
    public InvitationIssueServiceImpl(
            InvitationMapper invitationMapper,
            InvitationTokenCodec tokenCodec,
            InvitationLinkFactory linkFactory,
            BadgeApplicationService badgeApplicationService) {
        this(
                invitationMapper,
                tokenCodec,
                linkFactory,
                badgeApplicationService,
                Clock.system(DATABASE_ZONE));
    }

    /** 시작 시각 경계를 검증할 때만 고정 Clock을 주입합니다. */
    InvitationIssueServiceImpl(
            InvitationMapper invitationMapper,
            InvitationTokenCodec tokenCodec,
            InvitationLinkFactory linkFactory,
            BadgeApplicationService badgeApplicationService,
            Clock clock) {
        this.invitationMapper = invitationMapper;
        this.tokenCodec = tokenCodec;
        this.linkFactory = linkFactory;
        this.badgeApplicationService = badgeApplicationService;
        this.clock = clock;
    }

    @Override
    @Transactional
    public InvitationIssueResult issue(AuthPrincipal principal, long workCaseId) {
        InvitationWorkCaseLockRow workCase = lockIssuableWorkCase(principal, workCaseId);
        LocalDateTime now = LocalDateTime.now(clock);

        // 만료된 PENDING이 남아 있으면 활성 초대 Unique 제약이 새 발급을 막습니다.
        invitationMapper.expireOverduePending(workCaseId, now);

        InvitationRow active =
                invitationMapper.findActivePendingByWorkCaseIdForUpdate(workCaseId);
        if (active != null) {
            if (active.getExpectedTermsVersion().equals(workCase.getTermsVersion())) {
                return InvitationIssueResult.existing(toResponse(active.getId(), active));
            }
            // 조건이 바뀌었는데도 이전 Version의 PENDING이 남아 있으면 그 Link는 더 이상
            // 사용할 수 없습니다. 활성 Slot을 비워야 현재 조건의 초대를 만들 수 있습니다.
            revokeActiveInvitation(workCaseId, now);
        }

        return InvitationIssueResult.created(createInvitation(workCase));
    }

    @Override
    @Transactional
    public InvitationIssueResponse reissue(AuthPrincipal principal, long workCaseId) {
        InvitationWorkCaseLockRow workCase = lockIssuableWorkCase(principal, workCaseId);
        LocalDateTime now = LocalDateTime.now(clock);

        invitationMapper.expireOverduePending(workCaseId, now);

        InvitationRow active =
                invitationMapper.findActivePendingByWorkCaseIdForUpdate(workCaseId);
        boolean replaceable = active != null
                && active.getExpectedTermsVersion().equals(workCase.getTermsVersion());
        if (!replaceable) {
            // 교체할 대상이 없습니다. 이 경우 새로 만들어 주면 재발급과 일반 발급의 구분이
            // 사라지고, 호출자는 기존 Link가 무효가 됐는지 알 수 없게 됩니다.
            throw new ConflictException(UNUSABLE_INVITATION);
        }

        // 철회와 새 발급이 한 Transaction에 있어야 두 Link가 동시에 유효한 순간이 없습니다.
        revokeActiveInvitation(workCaseId, now);
        return createInvitation(workCase);
    }

    /** 잠근 활성 초대가 사라졌다면 새 Link를 만들지 않고 Transaction을 중단합니다. */
    private void revokeActiveInvitation(long workCaseId, LocalDateTime now) {
        if (invitationMapper.revokePendingByWorkCaseId(workCaseId, now) != 1) {
            throw new IllegalStateException("잠근 PENDING 초대를 철회하지 못했습니다.");
        }
    }

    /**
     * 근무 행을 잠그고 소유권과 발급 가능 상태를 확인합니다.
     *
     * <p>존재하지 않는 근무와 다른 OWNER의 근무를 같은 404로 응답합니다. 403으로 구분하면
     * "존재는 하지만 내 것이 아니다"라는 사실이 노출됩니다.</p>
     */
    private InvitationWorkCaseLockRow lockIssuableWorkCase(
            AuthPrincipal principal,
            long workCaseId) {
        if (principal.getRole() != UserRole.OWNER) {
            throw new RoleMismatchException("초대는 OWNER만 발급할 수 있습니다.");
        }

        InvitationWorkCaseLockRow workCase = invitationMapper.lockWorkCaseForIssue(workCaseId);
        if (workCase == null || !workCase.getEmployerId().equals(principal.getUserId())) {
            throw new ResourceNotFoundException(WORK_CASE_NOT_FOUND);
        }

        WorkCaseDecision decision = WorkCasePolicy.decideInvitationIssue(
                workCase.getStatus(),
                workCase.getWorkerId(),
                workCase.getStartsAt(),
                LocalDateTime.now(clock));
        if (decision != WorkCaseDecision.ALLOWED) {
            // 세 조건을 하나의 오류로 합칩니다. 어느 조건에서 걸렸는지 알려 주면 아직
            // 수락되지 않은 근무인지 같은 정보가 호출자에게 새어 나갑니다.
            throw new WorkCaseLockedException(NOT_ISSUABLE);
        }
        return workCase;
    }

    /**
     * 새 초대를 만들고 확정된 Token Hash까지 같은 Transaction에서 기록합니다.
     *
     * <p>Token 원문은 저장된 초대 ID에서 파생하므로 INSERT로 ID를 먼저 확보합니다. 발급하는
     * OWNER의 배지도 이 시점에 같은 Transaction에서 재계산해 둡니다 — 초대 조회 쪽은 이 값을
     * 읽기만 하므로, 여기서 한 번 최신화해 두지 않으면 초대에 계속 낡은 배지가 붙습니다.</p>
     */
    private InvitationIssueResponse createInvitation(InvitationWorkCaseLockRow workCase) {
        badgeApplicationService.recalculate(workCase.getEmployerId());

        InvitationInsertParam param = InvitationInsertParam.builder()
                .workCaseId(workCase.getWorkCaseId())
                // 조건 Version과 만료는 잠근 근무에서 복사합니다. 호출자는 지정할 수 없습니다.
                .expectedTermsVersion(workCase.getTermsVersion())
                .expiresAt(workCase.getStartsAt())
                .build();

        try {
            invitationMapper.insertPending(param);
        } catch (DuplicateKeyException conflicting) {
            // 근무 행을 잠그고 있어 같은 근무의 동시 발급은 여기까지 오지 못합니다. 그
            // 가정이 깨지더라도 원시 SQL 예외 대신 승인된 충돌로 끝냅니다.
            throw new ConflictException(UNUSABLE_INVITATION);
        }

        Long invitationId = Objects.requireNonNull(param.getId(), "생성된 초대 식별자");
        String token = tokenCodec.deriveToken(invitationId);
        if (invitationMapper.updateTokenHash(invitationId, tokenCodec.hash(token)) != 1) {
            throw new IllegalStateException("생성한 PENDING 초대의 Token Hash를 확정하지 못했습니다.");
        }

        return InvitationIssueResponse.of(
                linkFactory.toInviteUrl(token),
                ApiTimes.toInstant(workCase.getStartsAt())
        );
    }

    /**
     * 활성 초대의 Link를 다시 만들어 같은 응답을 재현합니다.
     *
     * <p>저장소에는 Hash만 있으므로 원문은 초대 ID에서 다시 파생합니다. Key 교체 중이면
     * 이전 Key로도 시도하며, 어느 Key로도 저장된 Hash와 맞지 않으면 그 Link는 재현할 수
     * 없으므로 재발급으로 안내합니다.</p>
     */
    private InvitationIssueResponse toResponse(long invitationId, InvitationRow active) {
        String token = tokenCodec.reproduceToken(invitationId, active.getTokenHash())
                .orElseThrow(() -> new ConflictException(UNUSABLE_INVITATION));

        return InvitationIssueResponse.of(
                linkFactory.toInviteUrl(token),
                ApiTimes.toInstant(active.getExpiresAt())
        );
    }
}
