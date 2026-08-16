package com.gighub.badge.service.result;

import lombok.Getter;

/**
 * 재계산 없이 마지막으로 저장된 배지 값만 담는 읽기 전용 결과입니다.
 *
 * <p>{@code BadgeCalculationResult}와 달리 다음 등급까지 남은 건수 같은, evidence JSON에
 * 저장되지 않는 값은 담지 않습니다.</p>
 */
@Getter
public final class BadgeSnapshot {

    private final String badgeType;
    private final int level;

    private BadgeSnapshot(String badgeType, int level) {
        this.badgeType = badgeType;
        this.level = level;
    }

    public static BadgeSnapshot of(String badgeType, int level) {
        return new BadgeSnapshot(badgeType, level);
    }
}
