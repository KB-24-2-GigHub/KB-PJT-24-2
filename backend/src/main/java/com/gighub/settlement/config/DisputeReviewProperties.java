package com.gighub.settlement.config;

import com.gighub.settlement.review.DisputeReviewDecision;
import com.gighub.settlement.review.DisputeReviewMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/** 외부 분쟁 검토를 기본 비활성으로 두고 DEMO 실행값만 읽습니다. */
@Component
public class DisputeReviewProperties {

    public static final String MODE_KEY = "dispute.review.mode";
    public static final String DEMO_CONFIRMED_KEY = "dispute.review.demo-confirmed";
    public static final String FAKE_DECISION_KEY = "dispute.review.fake-decision";
    public static final String OPENAI_API_KEY = "dispute.review.openai.api-key";
    public static final String OPENAI_MODEL_KEY = "dispute.review.openai.model";
    public static final String PROMPT_VERSION_KEY = "dispute.review.prompt-version";
    public static final String TIMEOUT_MS_KEY = "dispute.review.timeout-ms";
    public static final String LEASE_MS_KEY = "dispute.review.lease-ms";
    public static final String MAX_ATTEMPTS_KEY = "dispute.review.max-attempts";
    public static final String BATCH_SIZE_KEY = "dispute.review.batch-size";
    public static final String FIXED_DELAY_MS_KEY = "dispute.review.fixed-delay-ms";
    public static final String INITIAL_DELAY_MS_KEY = "dispute.review.initial-delay-ms";

    public static final long DEFAULT_TIMEOUT_MS = 10_000L;
    public static final long DEFAULT_LEASE_MS = 30_000L;
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    public static final int DEFAULT_BATCH_SIZE = 20;
    public static final long DEFAULT_FIXED_DELAY_MS = 1_000L;
    public static final long DEFAULT_INITIAL_DELAY_MS = 1_000L;

    private final DisputeReviewMode mode;
    private final DisputeReviewDecision fakeDecision;
    private final String apiKey;
    private final String model;
    private final String promptVersion;
    private final Duration timeout;
    private final Duration lease;
    private final int maxAttempts;
    private final int batchSize;
    private final Duration fixedDelay;
    private final Duration initialDelay;

    @Autowired
    public DisputeReviewProperties(Environment environment) {
        this(
                parseMode(environment.getProperty(MODE_KEY, DisputeReviewMode.DISABLED.name())),
                environment.getProperty(DEMO_CONFIRMED_KEY, Boolean.class, false),
                parseDecision(environment.getProperty(
                        FAKE_DECISION_KEY,
                        DisputeReviewDecision.NEEDS_MORE_INFO.name())),
                environment.getProperty(OPENAI_API_KEY),
                environment.getProperty(OPENAI_MODEL_KEY),
                environment.getProperty(PROMPT_VERSION_KEY, "dispute-review-v1"),
                environment.getProperty(TIMEOUT_MS_KEY, Long.class, DEFAULT_TIMEOUT_MS),
                environment.getProperty(LEASE_MS_KEY, Long.class, DEFAULT_LEASE_MS),
                environment.getProperty(BATCH_SIZE_KEY, Integer.class, DEFAULT_BATCH_SIZE),
                environment.getProperty(FIXED_DELAY_MS_KEY, Long.class, DEFAULT_FIXED_DELAY_MS),
                environment.getProperty(INITIAL_DELAY_MS_KEY, Long.class, DEFAULT_INITIAL_DELAY_MS),
                environment.getProperty(MAX_ATTEMPTS_KEY, Integer.class, DEFAULT_MAX_ATTEMPTS)
        );
    }

    DisputeReviewProperties(
            DisputeReviewMode mode,
            boolean demoConfirmed,
            DisputeReviewDecision fakeDecision,
            String apiKey,
            String model,
            String promptVersion,
            long timeoutMs,
            long leaseMs,
            int batchSize,
            long fixedDelayMs,
            long initialDelayMs,
            int maxAttempts) {
        this.mode = requireMode(mode, demoConfirmed);
        this.fakeDecision = requireNonNull(fakeDecision, FAKE_DECISION_KEY);
        this.apiKey = trimToNull(apiKey);
        this.model = trimToNull(model);
        this.promptVersion = requireText(promptVersion, PROMPT_VERSION_KEY);
        this.timeout = Duration.ofMillis(requirePositive(TIMEOUT_MS_KEY, timeoutMs));
        this.lease = Duration.ofMillis(requireLease(leaseMs, timeoutMs));
        this.maxAttempts = requireMaxAttempts(maxAttempts);
        this.batchSize = requireBatchSize(batchSize);
        this.fixedDelay = Duration.ofMillis(requirePositive(FIXED_DELAY_MS_KEY, fixedDelayMs));
        this.initialDelay = Duration.ofMillis(requirePositive(INITIAL_DELAY_MS_KEY, initialDelayMs));
        if (mode == DisputeReviewMode.DEMO_LLM) {
            requireText(this.apiKey, OPENAI_API_KEY);
            requireText(this.model, OPENAI_MODEL_KEY);
        }
    }

    public boolean isEnabled() {
        return mode != DisputeReviewMode.DISABLED;
    }

    public DisputeReviewMode getMode() {
        return mode;
    }

    public DisputeReviewDecision getFakeDecision() {
        return fakeDecision;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getModel() {
        return model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public Duration getLease() {
        return lease;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public Duration getFixedDelay() {
        return fixedDelay;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    private static DisputeReviewMode requireMode(
            DisputeReviewMode mode,
            boolean demoConfirmed) {
        DisputeReviewMode checked = requireNonNull(mode, MODE_KEY);
        if (checked != DisputeReviewMode.DISABLED && !demoConfirmed) {
            throw new IllegalStateException(
                    DEMO_CONFIRMED_KEY + "=true일 때만 DEMO 분쟁 검토를 켤 수 있습니다.");
        }
        return checked;
    }

    private static long requireLease(long leaseMs, long timeoutMs) {
        requirePositive(LEASE_MS_KEY, leaseMs);
        if (leaseMs <= timeoutMs) {
            throw new IllegalStateException(LEASE_MS_KEY + "는 Provider timeout보다 길어야 합니다.");
        }
        return leaseMs;
    }

    private static int requireBatchSize(int value) {
        if (value < 1 || value > 100) {
            throw new IllegalStateException(BATCH_SIZE_KEY + "는 1~100 범위여야 합니다.");
        }
        return value;
    }

    private static int requireMaxAttempts(int value) {
        if (value < 1 || value > 10) {
            throw new IllegalStateException(MAX_ATTEMPTS_KEY + "는 1~10 범위여야 합니다.");
        }
        return value;
    }

    private static long requirePositive(String key, long value) {
        if (value <= 0) {
            throw new IllegalStateException(key + "는 1 이상이어야 합니다.");
        }
        return value;
    }

    private static String requireText(String value, String key) {
        String checked = trimToNull(value);
        if (checked == null) {
            throw new IllegalStateException(key + " 설정이 필요합니다.");
        }
        return checked;
    }

    private static <T> T requireNonNull(T value, String key) {
        if (value == null) {
            throw new IllegalStateException(key + " 설정이 필요합니다.");
        }
        return value;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static DisputeReviewMode parseMode(String value) {
        try {
            return DisputeReviewMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalid) {
            throw new IllegalStateException(MODE_KEY + " 설정이 올바르지 않습니다.", invalid);
        }
    }

    private static DisputeReviewDecision parseDecision(String value) {
        try {
            return DisputeReviewDecision.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalid) {
            throw new IllegalStateException(
                    FAKE_DECISION_KEY + " 설정이 올바르지 않습니다.", invalid);
        }
    }
}
