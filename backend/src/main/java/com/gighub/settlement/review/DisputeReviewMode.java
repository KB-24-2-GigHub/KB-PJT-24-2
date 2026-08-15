package com.gighub.settlement.review;

/** 분쟁 검토 실행을 기본 비활성·결정적 Fake·외부 DEMO Provider로 분리합니다. */
public enum DisputeReviewMode {
    DISABLED,
    FAKE,
    DEMO_LLM
}
