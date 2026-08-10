package com.gighub.invitation.mapper;

import com.gighub.invitation.mapper.param.InvitationInsertParam;
import com.gighub.invitation.mapper.result.AcceptWorkCaseLockRow;
import com.gighub.invitation.mapper.result.InvitationRow;
import com.gighub.invitation.mapper.result.InvitationWorkCaseLockRow;
import com.gighub.invitation.mapper.result.InvitationWorkCaseRow;

import java.time.LocalDateTime;

/**
 * 모든 Method가 "부르면 안 된다"로 시작하는 {@link InvitationMapper} Test Double입니다.
 *
 * <p>각 테스트는 자기 흐름이 실제로 쓰는 Method만 재정의합니다. 그러면 예상하지 못한 호출이
 * 조용히 {@code null}을 돌려주는 대신 즉시 실패로 드러나고, Mapper에 Method가 늘어도 테스트
 * Stub을 한 곳에서만 고치면 됩니다.</p>
 */
public abstract class InvitationMapperTestDouble implements InvitationMapper {

    @Override
    public int insertPending(InvitationInsertParam param) {
        throw new UnsupportedOperationException("insertPending");
    }

    @Override
    public int updateTokenHash(long invitationId, byte[] tokenHash) {
        throw new UnsupportedOperationException("updateTokenHash");
    }

    @Override
    public InvitationRow findByTokenHash(byte[] tokenHash) {
        throw new UnsupportedOperationException("findByTokenHash");
    }

    @Override
    public InvitationRow findByTokenHashForUpdate(byte[] tokenHash) {
        throw new UnsupportedOperationException("findByTokenHashForUpdate");
    }

    @Override
    public InvitationRow lockInvitationById(long invitationId) {
        throw new UnsupportedOperationException("lockInvitationById");
    }

    @Override
    public InvitationRow findActivePendingByWorkCaseIdForUpdate(long workCaseId) {
        throw new UnsupportedOperationException("findActivePendingByWorkCaseIdForUpdate");
    }

    @Override
    public AcceptWorkCaseLockRow lockWorkCaseForAccept(long workCaseId) {
        throw new UnsupportedOperationException("lockWorkCaseForAccept");
    }

    @Override
    public InvitationWorkCaseLockRow lockWorkCaseForIssue(long workCaseId) {
        throw new UnsupportedOperationException("lockWorkCaseForIssue");
    }

    @Override
    public InvitationWorkCaseRow findWorkCaseForInvitation(long workCaseId) {
        throw new UnsupportedOperationException("findWorkCaseForInvitation");
    }

    @Override
    public int markAccepted(
            long invitationId,
            long acceptedByUserId,
            int acceptedTermsVersion,
            LocalDateTime acceptedAt) {
        throw new UnsupportedOperationException("markAccepted");
    }

    @Override
    public int markExpired(long invitationId) {
        throw new UnsupportedOperationException("markExpired");
    }

    @Override
    public int expireOverduePending(long workCaseId, LocalDateTime now) {
        throw new UnsupportedOperationException("expireOverduePending");
    }

    @Override
    public int revokePendingByWorkCaseId(long workCaseId, LocalDateTime revokedAt) {
        throw new UnsupportedOperationException("revokePendingByWorkCaseId");
    }

    @Override
    public int revokePendingByWorkCaseIdNow(long workCaseId) {
        throw new UnsupportedOperationException("revokePendingByWorkCaseIdNow");
    }
}
