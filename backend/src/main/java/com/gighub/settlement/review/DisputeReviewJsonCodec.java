package com.gighub.settlement.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gighub.config.ApiJsonMapper;

import java.util.List;

/** 사유 코드 배열을 JSON 감사 컬럼과 API 목록 사이에서 동일하게 변환합니다. */
public final class DisputeReviewJsonCodec {

    private static final ObjectMapper OBJECT_MAPPER = ApiJsonMapper.create();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private DisputeReviewJsonCodec() {
    }

    public static String writeReasonCodes(List<String> reasonCodes) {
        try {
            return OBJECT_MAPPER.writeValueAsString(reasonCodes);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("분쟁 검토 사유 코드를 JSON으로 만들 수 없습니다.", impossible);
        }
    }

    public static List<String> readReasonCodes(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return List.copyOf(OBJECT_MAPPER.readValue(json, STRING_LIST));
        } catch (JsonProcessingException malformed) {
            throw new IllegalStateException("저장된 분쟁 검토 사유 코드가 올바르지 않습니다.", malformed);
        }
    }
}
