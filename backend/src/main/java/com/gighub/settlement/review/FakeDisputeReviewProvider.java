package com.gighub.settlement.review;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** CI와 로컬 DEMO가 네트워크 없이 동일한 상태 전이를 재현하는 Provider입니다. */
public class FakeDisputeReviewProvider implements DisputeReviewProvider {

    private static final String PROVIDER = "FAKE";
    private static final String MODEL = "deterministic-dispute-demo-v1";
    private static final String DEFAULT_PROMPT_VERSION = "dispute-review-v1";

    private final DisputeReviewDecision decision;
    private final String promptVersion;

    public FakeDisputeReviewProvider(DisputeReviewDecision decision) {
        this(decision, DEFAULT_PROMPT_VERSION);
    }

    public FakeDisputeReviewProvider(
            DisputeReviewDecision decision,
            String promptVersion) {
        this.decision = Objects.requireNonNull(decision, "decision");
        if (promptVersion == null || promptVersion.isBlank()) {
            throw new IllegalArgumentException("promptVersion이 필요합니다.");
        }
        this.promptVersion = promptVersion.trim();
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public String modelName() {
        return MODEL;
    }

    @Override
    public String promptVersion() {
        return promptVersion;
    }

    @Override
    public DisputeReviewProviderResult review(String requestId, DisputeReviewInput input) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId가 필요합니다.");
        }
        Objects.requireNonNull(input, "input");
        DisputeReviewResult result = switch (decision) {
            case RESOLVE -> new DisputeReviewResult(
                    decision,
                    List.of("AGREED_WAGE_FLOW_REVIEWED"),
                    "약정 일급의 기존 정산 흐름을 재개합니다.",
                    new BigDecimal("0.990")
            );
            case REJECT -> new DisputeReviewResult(
                    decision,
                    List.of("CLAIM_NOT_SUPPORTED_IN_DEMO"),
                    "제공된 정보만으로 신고 사유를 인정하기 어렵습니다.",
                    new BigDecimal("0.990")
            );
            case NEEDS_MORE_INFO -> new DisputeReviewResult(
                    decision,
                    List.of("ADDITIONAL_EVIDENCE_NEEDED"),
                    "근태 또는 지급 자료를 추가로 확인해야 합니다.",
                    new BigDecimal("0.990")
            );
        };
        return new DisputeReviewProviderResult(
                "fake-" + UUID.randomUUID(),
                DisputeReviewResults.validate(result)
        );
    }
}
