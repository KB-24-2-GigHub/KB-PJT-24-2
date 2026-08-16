package com.gighub.badge.dto;

import com.gighub.badge.domain.TrustBadgeType;
import com.gighub.badge.service.result.BadgeCalculationResult;
import lombok.Getter;

/**
 * {@code GET /api/users/me/badge}가 반환하는 승인 Shape입니다.
 *
 * <p>{@code recentCount}는 SPEC-178-06이 정한 호환 필드명이며 값은 누적 건수입니다.</p>
 */
@Getter
public final class BadgeResponse {

    private final String badgeType;
    private final int level;
    private final long recentCount;
    private final long remainingToNextLevel;
    private final String criterionLabel;
    private final String criterionDesc;

    private BadgeResponse(
            String badgeType,
            int level,
            long recentCount,
            long remainingToNextLevel,
            String criterionLabel,
            String criterionDesc) {
        this.badgeType = badgeType;
        this.level = level;
        this.recentCount = recentCount;
        this.remainingToNextLevel = remainingToNextLevel;
        this.criterionLabel = criterionLabel;
        this.criterionDesc = criterionDesc;
    }

    public static BadgeResponse of(BadgeCalculationResult result) {
        TrustBadgeType badgeType = TrustBadgeType.valueOf(result.getBadgeType());
        return new BadgeResponse(
                result.getBadgeType(),
                result.getLevel(),
                result.getTotalCount(),
                result.getRemainingToNextLevel(),
                badgeType.getCriterionLabel(),
                buildCriterionDesc(result));
    }

    /**
     * 등급별 안내 문장을 조립합니다.
     *
     * <ul>
     *   <li>3단계: 최고 등급 안내</li>
     *   <li>건수 미충족(remaining&gt;0): 다음 등급까지 남은 건수 안내</li>
     *   <li>건수는 채웠지만 비율 부족(remaining=0, level&lt;3): 부족한 비율 안내</li>
     * </ul>
     */
    private static String buildCriterionDesc(BadgeCalculationResult result) {
        if (result.getLevel() >= 3) {
            return "누적 %d건과 정상 비율을 기준으로 최고 등급입니다.".formatted(result.getTotalCount());
        }
        if (result.getRemainingToNextLevel() > 0) {
            return "누적 %d건과 정상 비율을 기준으로 산정했습니다. 다음 등급까지 %d건이 남았습니다."
                    .formatted(result.getTotalCount(), result.getRemainingToNextLevel());
        }
        return "누적 %d건은 채웠지만 정상 비율이 다음 등급 기준(%d%%)에 못 미칩니다."
                .formatted(result.getTotalCount(), result.getNextThresholdPercent());
    }
}
