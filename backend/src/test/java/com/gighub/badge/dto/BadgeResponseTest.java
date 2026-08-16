package com.gighub.badge.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gighub.badge.service.result.BadgeCalculationResult;
import org.junit.jupiter.api.Test;

class BadgeResponseTest {

    @Test
    void level3DescribesMaxLevel() {
        BadgeResponse response = BadgeResponse.of(BadgeCalculationResult.of(
                "TRUST_WORKER", 3, 30, 30, 30, 100, 0, 0));

        assertEquals("성실근로", response.getCriterionLabel());
        assertEquals("누적 30건과 정상 비율을 기준으로 최고 등급입니다.", response.getCriterionDesc());
    }

    @Test
    void countShortfallDescribesRemainingCount() {
        BadgeResponse response = BadgeResponse.of(BadgeCalculationResult.of(
                "TRUST_OWNER", 1, 12, 10, 10, 80, 8, 90));

        assertEquals("안심거래", response.getCriterionLabel());
        assertEquals(8, response.getRemainingToNextLevel());
        assertEquals(
                "누적 12건과 정상 비율을 기준으로 산정했습니다. 다음 등급까지 8건이 남았습니다.",
                response.getCriterionDesc());
    }

    @Test
    void ratioShortfallDescribesRequiredPercent() {
        // 20건 채웠지만 정상 비율이 2단계(90%) 기준에 못 미쳐 1단계에 머무는 경우
        BadgeResponse response = BadgeResponse.of(BadgeCalculationResult.of(
                "TRUST_WORKER", 1, 20, 17, 10, 80, 0, 90));

        assertEquals(0, response.getRemainingToNextLevel());
        assertEquals(
                "누적 20건은 채웠지만 정상 비율이 다음 등급 기준(90%)에 못 미칩니다.",
                response.getCriterionDesc());
    }

    @Test
    void noHistoryStillReturnsRoleScopedZeroLevelObject() {
        BadgeResponse response = BadgeResponse.of(BadgeCalculationResult.of(
                "TRUST_WORKER", 0, 0, 0, 0, 0, 10, 80));

        assertEquals("TRUST_WORKER", response.getBadgeType());
        assertEquals(0, response.getLevel());
        assertEquals(0, response.getRecentCount());
    }
}
