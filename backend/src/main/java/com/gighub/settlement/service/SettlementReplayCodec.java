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
                    || !data.hasNonNull("completedAt")) {
                throw new IllegalStateException("저장된 정산 승인 응답이 완전하지 않습니다.");
            }
            long settlementId = data.get("settlementId").asLong();
            String status = data.get("status").asText();
            Instant completedAt = Instant.parse(data.get("completedAt").asText());
            if (settlementId <= 0 || !"COMPLETED".equals(status)) {
                throw new IllegalStateException("저장된 정산 승인 응답 값이 올바르지 않습니다.");
            }
            return SettlementResult.builder()
                    .settlementId(settlementId)
                    .status(status)
                    .completedAt(ApiTimes.toLocalDateTime(completedAt))
                    .replayed(true)
                    .build();
        } catch (JsonProcessingException | RuntimeException exception) {
            if (exception instanceof IllegalStateException) {
                throw (IllegalStateException) exception;
            }
            throw new IllegalStateException("저장된 정산 승인 응답을 읽지 못했습니다.", exception);
        }
    }
}
