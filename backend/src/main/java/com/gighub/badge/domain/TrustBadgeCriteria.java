package com.gighub.badge.domain;

/**
 * SPEC-178-06의 누적 10·20·30건, 정상 비율 80·90·100%를 AND로 판정합니다.
 *
 * <p>3단계부터 내림차순으로 검사해 처음 만족한 등급을 적용합니다. 비율은 반올림하지 않고
 * {@code normalCount * 100 >= totalCount * thresholdPercent}로 비교합니다. OWNER와 WORKER
 * 모두 같은 문턱을 쓰므로 역할과 무관한 단일 계산기로 둡니다.</p>
 */
public final class TrustBadgeCriteria {

    public static final String RULE_VERSION = "trust-badge-cumulative-10-20-30-v1";

    /** index 0=3단계, 1=2단계, 2=1단계. 내림차순 검사 순서를 배열 순서 그대로 표현합니다. */
    private static final int[] LEVEL_THRESHOLD_COUNT = {30, 20, 10};
    private static final int[] LEVEL_THRESHOLD_PERCENT = {100, 90, 80};

    private TrustBadgeCriteria() {
    }

    public static TrustBadgeResult calculate(long totalCount, long normalCount) {
        validate(totalCount, normalCount);

        for (int index = 0; index < LEVEL_THRESHOLD_COUNT.length; index++) {
            int thresholdCount = LEVEL_THRESHOLD_COUNT[index];
            int thresholdPercent = LEVEL_THRESHOLD_PERCENT[index];
            if (totalCount >= thresholdCount
                    && normalCount * 100 >= totalCount * (long) thresholdPercent) {
                int level = 3 - index;
                return TrustBadgeResult.of(
                        level,
                        thresholdCount,
                        thresholdPercent,
                        totalCount,
                        normalCount,
                        remainingToNextLevel(level, totalCount));
            }
        }
        // 0단계: thresholdCount/thresholdPercent는 SPEC-178-06대로 0을 저장한다.
        return TrustBadgeResult.of(0, 0, 0, totalCount, normalCount, remainingToNextLevel(0, totalCount));
    }

    private static void validate(long totalCount, long normalCount) {
        if (totalCount < 0 || normalCount < 0 || normalCount > totalCount) {
            throw new IllegalArgumentException(
                    "totalCount(" + totalCount + ")/normalCount(" + normalCount + ")가 올바르지 않습니다.");
        }
    }

    /**
     * 다음 등급의 건수 문턱까지 남은 수입니다.
     *
     * <p>건수는 이미 충족했지만 비율이 부족해 등급이 오르지 못한 경우도
     * {@code max(0, thresholdCount - totalCount)}가 자연히 0이 되어 BADGE-002가 요구하는
     * "건수 충족·비율 부족 시 0"과 일치합니다.</p>
     */
    private static long remainingToNextLevel(int level, long totalCount) {
        if (level >= 3) {
            return 0;
        }
        int nextThresholdCount = LEVEL_THRESHOLD_COUNT[2 - level];
        return Math.max(0, nextThresholdCount - totalCount);
    }
}