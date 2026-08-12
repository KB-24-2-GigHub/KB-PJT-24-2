package com.gighub.settlement.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gighub.common.api.ApiResponse;
import com.gighub.common.api.ApiTimes;
import com.gighub.config.ApiJsonMapper;
import com.gighub.settlement.dto.SettlementApproveResponse;
import com.gighub.settlement.service.result.SettlementResult;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** 멱등 Claim에 저장할 정산 승인 응답을 운영 JSON 형태 그대로 오갑니다. */
@Component
public class SettlementReplayCodec {

    private final ObjectMapper objectMapper = ApiJsonMapper.create();

    public String writeResponseBody(SettlementResult result) {
        try {
            return objectMapper.writeValueAsString(
                    ApiResponse.of(SettlementApproveResponse.from(result)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("정산 승인 응답을 저장하지 못했습니다.", exception);
        }
    }

    public SettlementResult readResponseBody(String storedBody) {
        try {
            JsonNode data = objectMapper.readTree(storedBody).get("data");
            if (data == null
                    || !data.hasNonNull("settlementId")
                    || !data.hasNonNull("status")
                    || !data.hasNonNull("originalEscrowAmount")
                    || !data.hasNonNull("workerPaidAmount")
                    || !data.hasNonNull("ownerRefundAmount")
                    || !data.hasNonNull("completedAt")) {
                throw new IllegalStateException("저장된 정산 승인 응답이 완전하지 않습니다.");
            }
            long settlementId = requireLong(data.get("settlementId"), "settlementId");
            long originalEscrowAmount = requireLong(
                    data.get("originalEscrowAmount"), "originalEscrowAmount");
            long workerPaidAmount = requireLong(
                    data.get("workerPaidAmount"), "workerPaidAmount");
            long ownerRefundAmount = requireLong(
                    data.get("ownerRefundAmount"), "ownerRefundAmount");
            if (!data.get("status").isTextual()
                    || !data.get("completedAt").isTextual()) {
                throw new IllegalStateException("저장된 정산 승인 응답 형식이 올바르지 않습니다.");
            }
            String status = data.get("status").textValue();
            Instant completedAt = Instant.parse(data.get("completedAt").asText());
            if (settlementId <= 0
                    || !"COMPLETED".equals(status)
                    || originalEscrowAmount <= 0
                    || workerPaidAmount < 0
                    || ownerRefundAmount < 0
                    || !preservesEscrow(
                            originalEscrowAmount,
                            workerPaidAmount,
                            ownerRefundAmount)) {
                throw new IllegalStateException("저장된 정산 승인 응답 값이 올바르지 않습니다.");
            }
            SettlementResult result = SettlementResult.builder()
                    .settlementId(settlementId)
                    .status(status)
                    // 저장 Body의 original은 최초 실행 시 검증된 Settlement 금액과 같습니다.
                    .settlementAmount(originalEscrowAmount)
                    .originalEscrowAmount(originalEscrowAmount)
                    .workerPaidAmount(workerPaidAmount)
                    .ownerRefundAmount(ownerRefundAmount)
                    .completedAt(ApiTimes.toLocalDateTime(completedAt))
                    .replayed(true)
                    .build();
            SettlementApproveResponse.from(result);
            return result;
        } catch (JsonProcessingException | RuntimeException exception) {
            if (exception instanceof IllegalStateException) {
                throw (IllegalStateException) exception;
            }
            throw new IllegalStateException("저장된 정산 승인 응답을 읽지 못했습니다.", exception);
        }
    }

    private long requireLong(JsonNode value, String fieldName) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalStateException(
                    "저장된 정산 승인 응답의 " + fieldName + " 값이 정수가 아닙니다.");
        }
        return value.longValue();
    }

    private boolean preservesEscrow(long original, long paid, long refund) {
        try {
            return Math.addExact(paid, refund) == original;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }
}
