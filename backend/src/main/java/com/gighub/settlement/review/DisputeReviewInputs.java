package com.gighub.settlement.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    /** 상태 변화와 무관한 신고 제목·경위만 감사 행의 입력 식별자로 사용합니다. */
    public static String sha256(DisputeReviewInput input) {
        DisputeReviewInput sanitized = sanitize(input);
        ObjectNode userInput = OBJECT_MAPPER.createObjectNode();
        userInput.put("content", sanitized.getContent());
        userInput.put("title", sanitized.getTitle());
        return sha256(writeJson(userInput));
    }

    /** Provider가 실제로 본 근무·정산 상태까지 포함해 지연 응답 적용 여부를 판정합니다. */
    public static String snapshotSha256(DisputeReviewInput input) {
        return sha256(writeCanonicalJson(sanitize(input)));
    }

    private static String sha256(String canonical) {
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK가 SHA-256을 제공하지 않습니다.", impossible);
        }
    }

    static String writeCanonicalJson(DisputeReviewInput input) {
        return writeJson(input);
    }

    private static String writeJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("분쟁 검토 입력 Snapshot을 만들 수 없습니다.", impossible);
        }
    }
}
