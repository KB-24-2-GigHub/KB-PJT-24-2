package com.gighub.badge.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TrustBadgeCriteriaTest {

    @Test
    void noHistoryIsLevelZeroWithZeroThresholds() {
        TrustBadgeResult result = TrustBadgeCriteria.calculate(0, 0);

        assertEquals(0, result.getLevel());
        assertEquals(0, result.getThresholdCount());
        assertEquals(0, result.getThresholdPercent());
        assertEquals(10, result.getRemainingToNextLevel());
    }

    @Test
    void justBelowCountBoundaryStaysAtPreviousLevel() {
        // 9건 모두 정상이어도 건수 10 미만이면 0단계다.
        TrustBadgeResult result = TrustBadgeCriteria.calculate(9, 9);

        assertEquals(0, result.getLevel());
        assertEquals(1, result.getRemainingToNextLevel());
    }

    @Test
    void meetsCountButRatioTooLowStaysBelowAndRemainingIsZero() {
        // 건수 10은 채웠지만 정상 비율 70% < 80%라 1단계에 오르지 못한다.
        TrustBadgeResult result = TrustBadgeCriteria.calculate(10, 7);

        assertEquals(0, result.getLevel());
        assertEquals(0, result.getRemainingToNextLevel());
    }

    @Test
    void exactBoundaryReachesLevelOne() {
        TrustBadgeResult result = TrustBadgeCriteria.calculate(10, 8);

        assertEquals(1, result.getLevel());
        assertEquals(10, result.getThresholdCount());
        assertEquals(80, result.getThresholdPercent());
        assertEquals(10, result.getRemainingToNextLevel());
    }

    @Test
    void countMeetsLevelTwoButRatioOnlyLevelOneFallsBackWithZeroRemaining() {
        // 20건 중 17건 정상(85%)은 2단계(90%) 기준에 못 미쳐 1단계로 판정되고,
        // 다음 등급 건수(20)는 이미 채웠으므로 remaining은 0이다.
        TrustBadgeResult result = TrustBadgeCriteria.calculate(20, 17);

        assertEquals(1, result.getLevel());
        assertEquals(0, result.getRemainingToNextLevel());
    }

    @Test
    void exactBoundaryReachesLevelTwo() {
        TrustBadgeResult result = TrustBadgeCriteria.calculate(20, 18);

        assertEquals(2, result.getLevel());
        assertEquals(20, result.getThresholdCount());
        assertEquals(90, result.getThresholdPercent());
        assertEquals(10, result.getRemainingToNextLevel());
    }

    @Test
    void exactBoundaryReachesLevelThreeWithZeroRemaining() {
        TrustBadgeResult result = TrustBadgeCriteria.calculate(30, 30);

        assertEquals(3, result.getLevel());
        assertEquals(30, result.getThresholdCount());
        assertEquals(100, result.getThresholdPercent());
        assertEquals(0, result.getRemainingToNextLevel());
    }

    @Test
    void countMeetsLevelThreeButRatioBelow100FallsBackToLevelTwo() {
        TrustBadgeResult result = TrustBadgeCriteria.calculate(30, 29);

        assertEquals(2, result.getLevel());
        assertEquals(0, result.getRemainingToNextLevel());
    }

    @Test
    void rejectsInvalidCounts() {
        assertThrows(IllegalArgumentException.class, () -> TrustBadgeCriteria.calculate(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> TrustBadgeCriteria.calculate(5, 6));
    }
}