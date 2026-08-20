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
            CalculationFields calculation = readCalculationFields(data, ownerRefundAmount);
            if (!data.get("status").isTextual()
                    || !data.get("completedAt").isTextual()) {
                throw new IllegalStateException("저장된 정산 승인 응답 형식이 올바르지 않습니다.");
            }
            String status = data.get("status").textValue();
            Instant completedAt = Instant.parse(data.get("completedAt").asText());
            if (settlementId <= 0
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
                    .deductionAmount(calculation.deductionAmount())
                    .deductionBaseMinutes(calculation.deductionBaseMinutes())
                    .lateMinutes(calculation.lateMinutes())
                    .earlyLeaveMinutes(calculation.earlyLeaveMinutes())
                    .calculationReason(calculation.calculationReason())
                    .calculationVersion(calculation.calculationVersion())
                    .calculatedAt(calculation.calculatedAt())
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

    /** 신규 계산 필드가 하나라도 있으면 전부 같은 응답 Version으로 저장되어야 합니다. */
    private CalculationFields readCalculationFields(JsonNode data, long ownerRefundAmount) {
        String[] fieldNames = {
                "deductionAmount",
                "deductionBaseMinutes",
                "lateMinutes",
                "earlyLeaveMinutes",
                "calculationReason",
                "calculationVersion",
                "calculatedAt"
        };
        int present = 0;
        for (String fieldName : fieldNames) {
            if (data.has(fieldName)) {
                present++;
            }
        }
        if (present == 0) {
            // 이 Patch 배포 전에 완료되어 보존 중인 응답 Body입니다. 금액은 기존 Body에서
            // 검증하고, 계산 근거는 없는 채로 한 번만 호환 Replay합니다.
            return new CalculationFields(
                    ownerRefundAmount, null, null, null, null, null, null);
        }
        if (present != fieldNames.length) {
            throw new IllegalStateException("저장된 정산 승인 계산 응답이 완전하지 않습니다.");
        }

        long deductionAmount = requireLong(data.get("deductionAmount"), "deductionAmount");
        Long deductionBaseMinutes = nullableLong(
                data.get("deductionBaseMinutes"), "deductionBaseMinutes");
        Long lateMinutes = nullableLong(data.get("lateMinutes"), "lateMinutes");
        Long earlyLeaveMinutes = nullableLong(
                data.get("earlyLeaveMinutes"), "earlyLeaveMinutes");
        String reason = requireText(data.get("calculationReason"), "calculationReason");
        String version = requireText(data.get("calculationVersion"), "calculationVersion");
        String calculatedAt = requireText(data.get("calculatedAt"), "calculatedAt");
        return new CalculationFields(
                deductionAmount,
                deductionBaseMinutes,
                lateMinutes,
                earlyLeaveMinutes,
                reason,
                version,
                ApiTimes.toLocalDateTime(Instant.parse(calculatedAt)));
    }

    private Long nullableLong(JsonNode value, String fieldName) {
        if (value == null || value.isNull()) {
            return null;
        }
        return requireLong(value, fieldName);
    }

    private String requireText(JsonNode value, String fieldName) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException(
                    "저장된 정산 승인 응답의 " + fieldName + " 값이 문자열이 아닙니다.");
        }
        return value.textValue();
    }

    private boolean preservesEscrow(long original, long paid, long refund) {
        try {
            return Math.addExact(paid, refund) == original;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private record CalculationFields(
            Long deductionAmount,
            Long deductionBaseMinutes,
            Long lateMinutes,
            Long earlyLeaveMinutes,
            String calculationReason,
            String calculationVersion,
            java.time.LocalDateTime calculatedAt) {
    }
}
