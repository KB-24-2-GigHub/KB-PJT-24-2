package com.gighub.attendance.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gighub.attendance.dto.AttendanceScanConfirmationResponse;
import com.gighub.attendance.dto.AttendanceScanResponse;
import com.gighub.attendance.dto.AttendanceScanResult;
import com.gighub.common.api.ApiResponse;
import com.gighub.config.ApiJsonMapper;

import org.springframework.stereotype.Component;

/**
 * 멱등 Claim에 저장할 스캔 응답 Snapshot을 JSON으로 오갑니다.
 *
 * <p>저장 값은 24시간 뒤에도 그대로 다시 나가므로 운영 응답과 같은 규칙(UTC
 * {@code Instant})으로 굳힙니다. 규칙이 다르면 최초 응답과 Replay 응답의 시각 표기가
 * 갈라집니다.</p>
 *
 * <p>{@link AttendanceScanResult}는 {@code RECORDED}와 {@code CONFIRMATION_REQUIRED} 두
 * 형태로 나뉘므로, 되읽을 때는 저장된 {@code result} 판별 필드로 어느 타입인지 먼저 정한
 * 뒤 그 타입으로 역직렬화합니다.</p>
 *
 * <p>Replay는 현재 도메인 상태를 다시 보지 않고 저장한 값만 씁니다.</p>
 */
@Component
public class AttendanceScanReplayCodec {

    private static final String RECORDED = "RECORDED";

    private final ObjectMapper objectMapper = ApiJsonMapper.create();

    public String writeResponseBody(AttendanceScanResult result) {
        try {
            return objectMapper.writeValueAsString(ApiResponse.of(result));
        } catch (JsonProcessingException exception) {
            // 삼키면 Claim에 빈 응답이 저장되어 Replay가 불가능해집니다.
            throw new IllegalStateException("근태 스캔 응답을 직렬화하지 못했습니다.", exception);
        }
    }

    public AttendanceScanResult readResponseBody(String storedBody) {
        try {
            JsonNode data = objectMapper.readTree(storedBody).get("data");
            if (data == null || data.get("result") == null) {
                throw new IllegalStateException("저장된 근태 스캔 응답에 data가 없습니다.");
            }
            Class<? extends AttendanceScanResult> type = RECORDED.equals(data.get("result").asText())
                    ? AttendanceScanResponse.class
                    : AttendanceScanConfirmationResponse.class;
            return objectMapper.treeToValue(data, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("저장된 근태 스캔 응답을 읽지 못했습니다.", exception);
        }
    }
}
