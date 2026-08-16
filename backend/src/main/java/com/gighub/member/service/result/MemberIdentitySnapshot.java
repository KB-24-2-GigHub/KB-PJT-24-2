package com.gighub.member.service.result;

/**
 * 계약 Snapshot 등 타 모듈에 공개할 수 있는 최소 회원 식별 정보입니다.
 *
 * <p>{@code phone}은 선택 입력이라 없을 수 있습니다.</p>
 */
public record MemberIdentitySnapshot(long userId, String name, String phone) {
}
