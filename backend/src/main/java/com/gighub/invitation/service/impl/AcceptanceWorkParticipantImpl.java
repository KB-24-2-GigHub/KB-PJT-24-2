package com.gighub.invitation.service.impl;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.api.ApiTimes;
import com.gighub.common.exception.ConflictException;
import com.gighub.common.exception.ForbiddenException;
import com.gighub.common.exception.WorkCaseLockedException;
import com.gighub.contract.domain.AcceptedContract;
import com.gighub.contract.domain.ContractTermsSnapshot;
import com.gighub.contract.mapper.WorkContractMapper;
import com.gighub.contract.mapper.param.WorkContractInsertParam;
import com.gighub.invitation.domain.InvitationDecision;
import com.gighub.invitation.domain.InvitationPolicy;
import com.gighub.invitation.exception.InvitationAlreadyAcceptedException;
import com.gighub.invitation.exception.InvitationExpiredException;
import com.gighub.invitation.exception.InvitationNotFoundException;
import com.gighub.invitation.exception.InvitationRevokedException;
import com.gighub.invitation.exception.InvitationTermsChangedException;
import com.gighub.invitation.mapper.InvitationMapper;
import com.gighub.invitation.mapper.result.AcceptWorkCaseLockRow;
import com.gighub.invitation.mapper.result.InvitationRow;
import com.gighub.invitation.service.AcceptanceWorkParticipant;
import com.gighub.invitation.service.result.AcceptanceWorkContext;
import com.gighub.member.service.MemberIdentityQueryService;
import com.gighub.member.service.result.MemberIdentitySnapshot;
import com.gighub.work.domain.WorkCaseDecision;
import com.gighub.work.domain.WorkCasePolicy;
import com.gighub.work.mapper.WorkCaseMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Objects;

/** Work 논리 모듈의 세 owner Mapper를 수락 의미 명령 뒤에 캡슐화합니다. */
@Service
public class AcceptanceWorkParticipantImpl implements AcceptanceWorkParticipant {

    private static final String NOT_ACCEPTABLE = "확정할 수 없는 근무입니다.";
    private static final String UNUSABLE_INVITATION = "초대 상태를 다시 확인해 주세요.";

    private final InvitationMapper invitationMapper;
    private final WorkCaseMapper workCaseMapper;
    private final WorkContractMapper workContractMapper;
    private final MemberIdentityQueryService memberIdentityQueryService;
    private final AcceptJson acceptJson;

    public AcceptanceWorkParticipantImpl(
            InvitationMapper invitationMapper,
            WorkCaseMapper workCaseMapper,
            WorkContractMapper workContractMapper,
            MemberIdentityQueryService memberIdentityQueryService,
            AcceptJson acceptJson) {
        this.invitationMapper = invitationMapper;
        this.workCaseMapper = workCaseMapper;
        this.workContractMapper = workContractMapper;
        this.memberIdentityQueryService = memberIdentityQueryService;
        this.acceptJson = acceptJson;
    }

    @Override
    @Transactional(
            propagation = Propagation.MANDATORY,
            noRollbackFor = InvitationExpiredException.class)
    public AcceptanceWorkContext lockAndValidate(
            AuthPrincipal principal,
            long invitationId,
            long workCaseId,
            byte[] tokenHash,
            LocalDateTime now) {
        AcceptWorkCaseLockRow workCase = invitationMapper.lockWorkCaseForAccept(workCaseId);
        if (workCase == null) {
            throw new IllegalStateException("초대가 가리키는 근무를 찾을 수 없습니다.");
        }
        if (workCase.getEmployerId().equals(principal.getUserId())) {
            throw new ForbiddenException("본인이 등록한 근무는 수락할 수 없습니다.");
        }

        InvitationRow invitation = invitationMapper.lockInvitationById(invitationId);
        if (invitation == null
                || !invitation.getWorkCaseId().equals(workCaseId)
                || !MessageDigest.isEqual(invitation.getTokenHash(), tokenHash)) {
            throw new InvitationNotFoundException();
        }
        requireUsableInvitation(invitation, now);
        if (InvitationPolicy.decideTerms(
                invitation.getExpectedTermsVersion(), workCase.getTermsVersion())
                == InvitationDecision.TERMS_CHANGED) {
            throw new InvitationTermsChangedException();
        }
        if (WorkCasePolicy.decideInvitationAccept(
                workCase.getStatus(),
                workCase.getWorkerId(),
                workCase.getStartsAt(),
                now) != WorkCaseDecision.ALLOWED) {
            throw new WorkCaseLockedException(NOT_ACCEPTABLE);
        }

        return AcceptanceWorkContext.builder()
                .invitationId(invitation.getId())
                .workCaseId(workCase.getWorkCaseId())
                .employerId(workCase.getEmployerId())
                .workerId(workCase.getWorkerId())
                .status(workCase.getStatus())
                .termsVersion(workCase.getTermsVersion())
                .title(workCase.getTitle())
                .startsAt(workCase.getStartsAt())
                .endsAt(workCase.getEndsAt())
                .breakMinutes(workCase.getBreakMinutes())
                .breakPaid(Boolean.TRUE.equals(workCase.getBreakPaid()))
                .dailyWage(workCase.getDailyWage())
                .workplaceName(workCase.getWorkplaceName())
                .workplaceAddress(workCase.getWorkplaceAddress())
                .workplaceLatitude(workCase.getWorkplaceLatitude())
                .workplaceLongitude(workCase.getWorkplaceLongitude())
                .allowedRadiusMeters(workCase.getAllowedRadiusMeters())
                .build();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirm(
            AcceptanceWorkContext context, long workerId, LocalDateTime acceptedAt) {
        if (workCaseMapper.assignWorkerAndAccept(context.getWorkCaseId(), workerId) != 1) {
            throw new WorkCaseLockedException(NOT_ACCEPTABLE);
        }
        if (invitationMapper.markAccepted(
                context.getInvitationId(),
                workerId,
                context.getTermsVersion(),
                acceptedAt) != 1) {
            throw new InvitationAlreadyAcceptedException();
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AcceptedContract createContract(
            AcceptanceWorkContext context,
            long workerId,
            LocalDateTime acceptedAt) {
        MemberIdentitySnapshot employer = Objects.requireNonNull(
                memberIdentityQueryService.findById(context.getEmployerId()),
                "고용주 이름");
        MemberIdentitySnapshot worker = Objects.requireNonNull(
                memberIdentityQueryService.findById(workerId),
                "근로자 이름");
        ContractTermsSnapshot snapshot = ContractTermsSnapshot.builder()
                .termsVersion(context.getTermsVersion())
                .title(context.getTitle())
                .startsAt(ApiTimes.toInstant(context.getStartsAt()))
                .endsAt(ApiTimes.toInstant(context.getEndsAt()))
                .breakMinutes(context.getBreakMinutes())
                .breakPaid(context.isBreakPaid())
                .workplaceName(context.getWorkplaceName())
                .workplaceAddress(context.getWorkplaceAddress())
                .workplaceLatitude(context.getWorkplaceLatitude())
                .workplaceLongitude(context.getWorkplaceLongitude())
                .allowedRadiusMeters(context.getAllowedRadiusMeters())
                .dailyWage(context.getDailyWage())
                .owner(context.getEmployerId(), employer.name())
                .worker(workerId, worker.name())
                .build();
        WorkContractInsertParam param = WorkContractInsertParam.from(
                context.getWorkCaseId(), snapshot, acceptJson.writeSnapshot(snapshot), acceptedAt);
        if (workContractMapper.insert(param) != 1) {
            throw new IllegalStateException("계약 Snapshot을 저장하지 못했습니다.");
        }
        return AcceptedContract.of(
                context.getWorkCaseId(),
                Objects.requireNonNull(param.getId(), "생성된 계약 식별자"),
                acceptedAt,
                snapshot);
    }

    private void requireUsableInvitation(InvitationRow invitation, LocalDateTime now) {
        switch (InvitationPolicy.decideUse(
                invitation.getStatus(), invitation.getExpiresAt(), now)) {
            case USABLE -> {
                return;
            }
            case ALREADY_ACCEPTED -> throw new InvitationAlreadyAcceptedException();
            case REVOKED -> throw new InvitationRevokedException();
            case EXPIRED -> throw new InvitationExpiredException();
            case EXPIRE_NOW -> {
                if (invitationMapper.markExpired(invitation.getId()) != 1) {
                    throw new IllegalStateException("잠근 PENDING 초대를 만료시키지 못했습니다.");
                }
                throw new InvitationExpiredException();
            }
            case TERMS_CHANGED, UNSUPPORTED_STATUS ->
                    throw new ConflictException(UNUSABLE_INVITATION);
        }
    }
}
