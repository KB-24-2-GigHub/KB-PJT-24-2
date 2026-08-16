package com.gighub.badge.mapper.param;

import java.time.LocalDateTime;

import lombok.Getter;

/** {@code user_badges} Upsert에 필요한 최소 입력값입니다. */
@Getter
public final class UserBadgeUpsertParam {

    private final Long userId;
    private final String badgeType;
    private final String evidence;
    private final LocalDateTime awardedAt;

    private UserBadgeUpsertParam(
            Long userId, String badgeType, String evidence, LocalDateTime awardedAt) {
        this.userId = userId;
        this.badgeType = badgeType;
        this.evidence = evidence;
        this.awardedAt = awardedAt;
    }

    public static UserBadgeUpsertParam of(
            Long userId, String badgeType, String evidence, LocalDateTime awardedAt) {
        return new UserBadgeUpsertParam(userId, badgeType, evidence, awardedAt);
    }
}