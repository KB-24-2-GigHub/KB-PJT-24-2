package com.gighub.badge.service.result;

import lombok.Getter;

/**
 * 배지 재계산 결과를 모듈 경계 밖으로 전달하는 Application Result입니다.
 *
 * <p>Domain 객체({@code TrustBadgeResult})를 그대로 노출하지 않고, Controller와 Invitation
 * 모듈이 공통으로 필요한 값만 평평하게 담습니다.</p>
 */
@Getter
public final class BadgeCalculationResult {

    private final String badgeType;
    private final int level;
    private final long totalCount;
    private final long normalCount;
    private final int thresholdCount;
    private final int thresholdPercent;
    private final long remainingToNextLevel;

    private BadgeCalculationResult(
            String badgeType,
            int level,
            long totalCount,
            long normalCount,
            int thresholdCount,
            int thresholdPercent,
            long remainingToNextLevel) {
        this.badgeType = badgeType;
        this.level = level;
        this.totalCount = totalCount;
        this.normalCount = normalCount;
        this.thresholdCount = thresholdCount;
        this.thresholdPercent = thresholdPercent;
        this.remainingToNextLevel = remainingToNextLevel;
    }

    public static BadgeCalculationResult of(
            String badgeType,
            int level,
            long totalCount,
            long normalCount,
            int thresholdCount,
            int thresholdPercent,
            long remainingToNextLevel) {
        return new BadgeCalculationResult(
                badgeType, level, totalCount, normalCount, thresholdCount, thresholdPercent,
                remainingToNextLevel);
    }
}
