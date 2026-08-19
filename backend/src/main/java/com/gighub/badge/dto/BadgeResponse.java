package com.gighub.badge.dto;

import com.gighub.badge.domain.TrustBadgeType;
import com.gighub.badge.service.result.BadgeCalculationResult;
import lombok.Getter;

/**
 * {@code GET /api/users/me/badge}가 반환하는 승인 Shape입니다.
 *
 * <p>{@code recentCount}는 SPEC-178-06이 정한 호환 필드명이며 값은 누적 건수입니다.
 * {@code normalCount}는 SPEC-432-01이 추가한 정상 건수이며, {@code criterionDesc} 문장
 * 조립에 쓰던 값을 그대로 노출합니다.</p>
 */
@Getter
public final class BadgeResponse {

    private final String badgeType;
    private final int level;
    private final long recentCount;
    private final long normalCount;
    private final long remainingToNextLevel;
    private final String criterionLabel;
    private final String criterionDesc;

    private BadgeResponse(
            String badgeType,
            int level,
            long recentCount,
            long normalCount,
            long remainingToNextLevel,
            String criterionLabel,
            String criterionDesc) {
        this.badgeType = badgeType;
        this.level = level;
        this.recentCount = recentCount;
        this.normalCount = normalCount;
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
                result.getNormalCount(),
                result.getRemainingToNextLevel(),
                badgeType.getCriterionLabel(),
                buildCriterionDesc(result));
    }

    /**
     * 누적·정상 건수와 다음 등급의 건수·비율 조건을 함께 설명합니다(API_SPEC.md '최신 뱃지').
     *
     * <p>3단계는 다음 등급이 없으므로 누적·정상 건수만 안내합니다.</p>
     */
    private static String buildCriterionDesc(BadgeCalculationResult result) {
        if (result.getLevel() >= 3) {
            return "누적 %d건 중 정상 %d건으로 최고 등급입니다."
                    .formatted(result.getTotalCount(), result.getNormalCount());
        }
        return ("누적 %d건 중 정상 %d건입니다. 다음 등급은 누적 %d건 이상과 정상 비율 %d%% 이상이 "
                + "필요하고, 건수는 %d건 남았습니다.")
                .formatted(
                        result.getTotalCount(),
                        result.getNormalCount(),
                        result.getNextThresholdCount(),
                        result.getNextThresholdPercent(),
                        result.getRemainingToNextLevel());
    }
}
