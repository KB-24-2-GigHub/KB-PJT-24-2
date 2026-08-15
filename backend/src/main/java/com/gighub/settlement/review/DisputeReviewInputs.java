package com.gighub.settlement.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gighub.config.ApiJsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Provider 전송값과 감사 Hash가 항상 같은 비식별 Snapshot을 사용하게 합니다. */
public final class DisputeReviewInputs {

    private static final ObjectMapper OBJECT_MAPPER = ApiJsonMapper.create();

    private DisputeReviewInputs() {
    }

    public static DisputeReviewInput sanitize(DisputeReviewInput input) {
        Objects.requireNonNull(input, "input");
        return new DisputeReviewInput(
                DisputeReviewRedactor.redact(input.getTitle()),
                DisputeReviewRedactor.redact(input.getContent()),
                Objects.requireNonNull(input.getWorkCaseStatus(), "workCaseStatus"),
                Objects.requireNonNull(input.getSettlementStatus(), "settlementStatus"),
                Objects.requireNonNull(input.getAgreedWage(), "agreedWage"),
                Objects.requireNonNull(input.getSuccessfulCheckInCount(), "successfulCheckInCount")
        );
    }

    public static String sha256(DisputeReviewInput input) {
        byte[] canonical = writeCanonicalJson(sanitize(input)).getBytes(StandardCharsets.UTF_8);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK가 SHA-256을 제공하지 않습니다.", impossible);
        }
    }

    static String writeCanonicalJson(DisputeReviewInput input) {
        try {
            return OBJECT_MAPPER.writeValueAsString(input);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("분쟁 검토 입력 Snapshot을 만들 수 없습니다.", impossible);
        }
    }
}
