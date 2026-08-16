package com.gighub.badge.domain;

import lombok.Getter;

/**
 * {@link TrustBadgeCriteria}의 계산 결과 값입니다.
 *
 * <p>Framework·Persistence 의존이 없는 순수 Domain 값이며, evidence 저장과 API 응답은 이 값을
 * 그대로 소비합니다.</p>
 */
@Getter
public final class TrustBadgeResult {

    private final int level;
    private final int thresholdCount;
    private final int thresholdPercent;
    private final long totalCount;
    private final long normalCount;
    private final long remainingToNextLevel;
    private final int nextThresholdPercent;

    private TrustBadgeResult(
            int level,
            int thresholdCount,
            int thresholdPercent,
            long totalCount,
            long normalCount,
            long remainingToNextLevel,
            int nextThresholdPercent) {
        this.level = level;
        this.thresholdCount = thresholdCount;
        this.thresholdPercent = thresholdPercent;
        this.totalCount = totalCount;
        this.normalCount = normalCount;
        this.remainingToNextLevel = remainingToNextLevel;
        this.nextThresholdPercent = nextThresholdPercent;
    }

    static TrustBadgeResult of(
            int level,
            int thresholdCount,
            int thresholdPercent,
            long totalCount,
            long normalCount,
            long remainingToNextLevel,
            int nextThresholdPercent) {
        return new TrustBadgeResult(
                level, thresholdCount, thresholdPercent, totalCount, normalCount, remainingToNextLevel,
                nextThresholdPercent);
    }
}