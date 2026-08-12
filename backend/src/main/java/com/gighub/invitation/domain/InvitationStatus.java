package com.gighub.invitation.domain;

/**
 * 저장 가능한 초대 lifecycle 상태입니다.
 *
 * <p>상수 이름은 Flyway Head의 {@code ck_work_invitations_status} 값과 같아 MyBatis가 DB
 * 문자열을 별도 변환 없이 안전하게 매핑합니다. {@code REJECTED}는 legacy 저장 제약에만
 * 남아 있으며 제품은 거절 Operation을 제공하지 않습니다.</p>
 */
public enum InvitationStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    REVOKED,
    EXPIRED
}
