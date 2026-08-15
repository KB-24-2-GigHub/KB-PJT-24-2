package com.gighub.settlement.review;

import com.gighub.settlement.domain.SettlementStatus;
import com.gighub.work.domain.WorkCaseStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisputeReviewInputsTest {

    @Test
    void hashUsesTheSameRedactedSnapshotSentToProvider() {
        DisputeReviewInput raw = new DisputeReviewInput(
                "010-1234-5678로 연락",
                "계좌 123-456-789012 확인",
                WorkCaseStatus.COMPLETED,
                SettlementStatus.ON_HOLD,
                120_000L,
                1L
        );

        DisputeReviewInput sanitized = DisputeReviewInputs.sanitize(raw);
        String canonical = DisputeReviewInputs.writeCanonicalJson(sanitized);

        assertTrue(canonical.contains("REDACTED_PHONE"));
        assertTrue(canonical.contains("REDACTED_FINANCIAL_NUMBER"));
        assertFalse(canonical.contains("010-1234-5678"));
        assertEquals(64, DisputeReviewInputs.sha256(raw).length());
        assertEquals(
                DisputeReviewInputs.sha256(raw),
                DisputeReviewInputs.sha256(sanitized));
    }

    @Test
    void anyFactChangeProducesAnotherSnapshotHash() {
        DisputeReviewInput first = input("약정 일급 지급 여부를 확인해주세요.");
        DisputeReviewInput second = input("근태 자료를 추가로 확인해주세요.");

        assertNotEquals(
                DisputeReviewInputs.sha256(first),
                DisputeReviewInputs.sha256(second));
    }

    private static DisputeReviewInput input(String content) {
        return new DisputeReviewInput(
                "임금 확인",
                content,
                WorkCaseStatus.COMPLETED,
                SettlementStatus.ON_HOLD,
                120_000L,
                1L
        );
    }
}
