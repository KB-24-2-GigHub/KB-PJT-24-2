package com.gighub.settlement.review;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

/** Fake와 외부 Adapter가 같은 결과 검증을 사용하도록 닫힌 경계를 제공합니다. */
public final class DisputeReviewResults {

    private static final Pattern REASON_CODE = Pattern.compile("[A-Z0-9_]{1,50}");
    static final int MIN_REASON_CODES = 1;
    static final int MAX_REASON_CODES = 5;
    private static final int MAX_SUMMARY_LENGTH = 500;

    private DisputeReviewResults() {
    }

    public static DisputeReviewResult validate(DisputeReviewResult result) {
        if (result == null || result.getDecision() == null) {
            throw invalid();
        }
        List<String> reasonCodes = result.getReasonCodes();
        if (reasonCodes == null
                || reasonCodes.size() < MIN_REASON_CODES
                || reasonCodes.size() > MAX_REASON_CODES
                || reasonCodes.stream().anyMatch(code -> code == null
                || !REASON_CODE.matcher(code).matches())) {
            throw invalid();
        }
        String summary = result.getSummary() == null ? "" : result.getSummary().trim();
        if (summary.isEmpty() || summary.length() > MAX_SUMMARY_LENGTH) {
            throw invalid();
        }
        BigDecimal confidence = result.getConfidence();
        if (confidence == null
                || confidence.compareTo(BigDecimal.ZERO) < 0
                || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw invalid();
        }
        return new DisputeReviewResult(
                result.getDecision(),
                List.copyOf(reasonCodes),
                summary,
                confidence
        );
    }

    private static DisputeReviewProviderException invalid() {
        return new DisputeReviewProviderException(
                "INVALID_PROVIDER_OUTPUT",
                "분쟁 검토 Provider 응답 형식이 올바르지 않습니다."
        );
    }
}
