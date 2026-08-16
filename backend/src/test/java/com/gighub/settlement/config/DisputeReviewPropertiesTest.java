package com.gighub.settlement.config;

import com.gighub.settlement.review.DisputeReviewDecision;
import com.gighub.settlement.review.DisputeReviewMode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisputeReviewPropertiesTest {

    @Test
    void defaultsToDisabledWithoutAnyApiCredential() {
        DisputeReviewProperties properties =
                new DisputeReviewProperties(new MockEnvironment());

        assertFalse(properties.isEnabled());
        assertEquals(DisputeReviewMode.DISABLED, properties.getMode());
        assertEquals(3, properties.getMaxAttempts());
    }

    @Test
    void activeModeRequiresExplicitDemoConfirmation() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(DisputeReviewProperties.MODE_KEY, "FAKE");

        assertThrows(
                IllegalStateException.class,
                () -> new DisputeReviewProperties(environment));
    }

    @Test
    void fakeModeReadsDeterministicDecision() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(DisputeReviewProperties.MODE_KEY, "FAKE")
                .withProperty(DisputeReviewProperties.DEMO_CONFIRMED_KEY, "true")
                .withProperty(
                        DisputeReviewProperties.FAKE_DECISION_KEY,
                        "release_to_worker");

        DisputeReviewProperties properties = new DisputeReviewProperties(environment);

        assertTrue(properties.isEnabled());
        assertEquals(DisputeReviewDecision.RESOLVE, properties.getFakeDecision());
    }

    @Test
    void llmModeRequiresApiKeyAndExplicitModel() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(DisputeReviewProperties.MODE_KEY, "DEMO_LLM")
                .withProperty(DisputeReviewProperties.DEMO_CONFIRMED_KEY, "true");

        assertThrows(
                IllegalStateException.class,
                () -> new DisputeReviewProperties(environment));
    }

    @Test
    void leaseMustOutliveProviderTimeout() {
        assertThrows(
                IllegalStateException.class,
                () -> new DisputeReviewProperties(
                        DisputeReviewMode.FAKE,
                        true,
                        DisputeReviewDecision.NEEDS_MORE_INFO,
                        null,
                        null,
                        "dispute-review-v1",
                        10_000,
                        10_000,
                        20,
                        1_000,
                        1_000,
                        3));
    }

    @Test
    void retryAttemptsStayWithinSmallDemoBoundary() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(DisputeReviewProperties.MAX_ATTEMPTS_KEY, "11");

        assertThrows(
                IllegalStateException.class,
                () -> new DisputeReviewProperties(environment));
    }
}
