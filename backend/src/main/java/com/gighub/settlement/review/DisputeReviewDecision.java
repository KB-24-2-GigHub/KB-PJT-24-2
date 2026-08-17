package com.gighub.settlement.review;

public enum DisputeReviewDecision {
    RESOLVE("RELEASE_TO_WORKER"),
    REJECT("REFUND_TO_OWNER"),
    NEEDS_MORE_INFO("NEEDS_MORE_INFO");

    private final String externalValue;

    DisputeReviewDecision(String externalValue) {
        this.externalValue = externalValue;
    }

    /** DB의 기존 감사 값은 유지하고 Provider·API에는 실제 자금 방향을 드러냅니다. */
    public String externalValue() {
        return externalValue;
    }

    public static DisputeReviewDecision fromExternalValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("분쟁 검토 결정값이 필요합니다.");
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        for (DisputeReviewDecision decision : values()) {
            if (decision.externalValue.equals(normalized) || decision.name().equals(normalized)) {
                return decision;
            }
        }
        throw new IllegalArgumentException("지원하지 않는 분쟁 검토 결정값입니다.");
    }
}
