package com.gighub.invitation.service;

import com.gighub.contract.domain.AcceptedContract;
import com.gighub.invitation.service.result.AcceptanceWorkContext;

import java.time.LocalDateTime;

/** 수락 Orchestrator가 사용하는 Work·Invitation·Contract owner participant입니다. */
public interface AcceptanceWorkParticipant {

    /** Work→Invitation 순서로 잠그고 권한·상태·조건 Version·만료를 다시 검증합니다. */
    AcceptanceWorkContext lockAndValidate(
            long workerId,
            long invitationId,
            long workCaseId,
            byte[] tokenHash,
            LocalDateTime now);

    /** Work 배정과 Invitation 수락을 expected-state 조건으로 확정합니다. */
    void confirm(AcceptanceWorkContext context, long workerId, LocalDateTime acceptedAt);

    /** 잠근 조건과 같은 값으로 불변 Contract Snapshot을 저장합니다. */
    AcceptedContract createContract(
            AcceptanceWorkContext context,
            long workerId,
            LocalDateTime acceptedAt);
}
