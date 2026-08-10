package com.gighub.member.service.result;

/** 계약 Snapshot 등 타 모듈에 공개할 수 있는 최소 회원 식별 정보입니다. */
public record MemberIdentitySnapshot(long userId, String name) {
}
