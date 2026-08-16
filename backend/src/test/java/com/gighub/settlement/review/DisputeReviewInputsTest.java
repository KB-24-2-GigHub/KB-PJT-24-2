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
    void stateChangeKeepsUserInputHashButChangesProviderSnapshotHash() {
        DisputeReviewInput first = input("약정 일급 지급 여부를 확인해주세요.");
        DisputeReviewInput second = new DisputeReviewInput(
                first.getTitle(),
                first.getContent(),
                WorkCaseStatus.NO_SHOW,
                SettlementStatus.WAITING,
                first.getAgreedWage(),
                0L
        );

        assertEquals(
                DisputeReviewInputs.sha256(first),
                DisputeReviewInputs.sha256(second));
        assertNotEquals(
                DisputeReviewInputs.snapshotSha256(first),
                DisputeReviewInputs.snapshotSha256(second));
    }

    @Test
    void datesAndTimesRemainWhilePhoneAndAccountShapesAreRedacted() {
        String source = "2026-08-15 09시부터 18시까지, 계좌 123-456-789012, 010-1234-5678";

        String redacted = DisputeReviewRedactor.redact(source);

        assertTrue(redacted.contains("2026-08-15 09시"));
        assertTrue(redacted.contains("[REDACTED_FINANCIAL_NUMBER]"));
        assertTrue(redacted.contains("[REDACTED_PHONE]"));
    }

    @Test
    void canonicalJsonUsesStableAlphabeticPropertyOrder() {
        String canonical = DisputeReviewInputs.writeCanonicalJson(input("경위"));

        assertTrue(canonical.indexOf("agreedWage") < canonical.indexOf("content"));
        assertTrue(canonical.indexOf("content") < canonical.indexOf("settlementStatus"));
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
