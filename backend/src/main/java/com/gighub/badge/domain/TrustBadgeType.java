package com.gighub.badge.domain;

/** SPEC-178-06이 확정한 두 역할별 배지 유형과 표시 Label입니다. */
public enum TrustBadgeType {

    TRUST_OWNER("안심거래"),
    TRUST_WORKER("성실근로");

    private final String criterionLabel;

    TrustBadgeType(String criterionLabel) {
        this.criterionLabel = criterionLabel;
    }

    public String getCriterionLabel() {
        return criterionLabel;
    }
}