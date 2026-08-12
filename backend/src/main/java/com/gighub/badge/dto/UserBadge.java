package com.gighub.badge.dto;

import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public final class UserBadge {

    private final Long id;
    private final Long userId;
    private final String badgeType;
    private final String evidence;
    private final LocalDateTime createdAt;

    private UserBadge(
            Long id,
            Long userId,
            String badgeType,
            String evidence,
            LocalDateTime createdAt) {
        this.id = id;
        this.userId = userId;
        this.badgeType = badgeType;
        this.evidence = evidence;
        this.createdAt = createdAt;
    }

    /** #182가 대체하기 전까지 현재 복수 목록의 JSON 필드와 값을 그대로 보존합니다. */
    public static UserBadge of(
            Long id,
            Long userId,
            String badgeType,
            String evidence,
            LocalDateTime createdAt) {
        return new UserBadge(id, userId, badgeType, evidence, createdAt);
    }
}
