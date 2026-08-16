package com.gighub.badge.service;

import java.time.LocalDateTime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gighub.badge.domain.TrustBadgeCriteria;
import com.gighub.badge.domain.TrustBadgeResult;
import com.gighub.badge.domain.TrustBadgeType;
import com.gighub.config.ApiJsonMapper;
import org.springframework.stereotype.Component;

/**
 * SPEC-178-06이 정한 닫힌 evidence JSON 필드만 직렬화합니다.
 *
 * <p>{@code ruleVersion, badgeType, level, totalCount, normalCount, thresholdCount,
 * thresholdPercent, calculatedAt} 외의 값(원천 행 ID, 개인정보)은 절대 담지 않습니다.</p>
 */
@Component
public class BadgeEvidenceCodec {

    private final ObjectMapper objectMapper = ApiJsonMapper.create();

    public String writeEvidence(
            TrustBadgeType badgeType, TrustBadgeResult result, LocalDateTime calculatedAt) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ruleVersion", TrustBadgeCriteria.RULE_VERSION);
        node.put("badgeType", badgeType.name());
        node.put("level", result.getLevel());
        node.put("totalCount", result.getTotalCount());
        node.put("normalCount", result.getNormalCount());
        node.put("thresholdCount", result.getThresholdCount());
        node.put("thresholdPercent", result.getThresholdPercent());
        node.put("calculatedAt", calculatedAt.toString());
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("배지 evidence를 직렬화하지 못했습니다.", exception);
        }
    }
}