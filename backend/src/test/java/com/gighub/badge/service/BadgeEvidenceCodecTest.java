package com.gighub.badge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gighub.badge.domain.TrustBadgeCriteria;
import com.gighub.badge.domain.TrustBadgeResult;
import com.gighub.badge.domain.TrustBadgeType;
import com.gighub.config.ApiJsonMapper;
import org.junit.jupiter.api.Test;

class BadgeEvidenceCodecTest {

    private final BadgeEvidenceCodec codec = new BadgeEvidenceCodec();
    private final ObjectMapper objectMapper = ApiJsonMapper.create();

    @Test
    void writesOnlySpecApprovedEvidenceFields() throws Exception {
        TrustBadgeResult result = TrustBadgeCriteria.calculate(12, 10);
        LocalDateTime calculatedAt = LocalDateTime.of(2026, 8, 15, 21, 30);

        String json = codec.writeEvidence(TrustBadgeType.TRUST_WORKER, result, calculatedAt);
        JsonNode node = objectMapper.readTree(json);

        assertEquals(
                Set.of(
                        "ruleVersion", "badgeType", "level", "totalCount", "normalCount",
                        "thresholdCount", "thresholdPercent", "calculatedAt"),
                fieldNames(node));
        assertEquals("trust-badge-cumulative-10-20-30-v1", node.path("ruleVersion").asText());
        assertEquals("TRUST_WORKER", node.path("badgeType").asText());
        assertEquals(1, node.path("level").asInt());
        assertEquals(12, node.path("totalCount").asLong());
        assertEquals(10, node.path("normalCount").asLong());
        assertEquals(10, node.path("thresholdCount").asInt());
        assertEquals(80, node.path("thresholdPercent").asInt());
        assertEquals("2026-08-15T21:30", node.path("calculatedAt").asText());
    }

    @Test
    void zeroLevelStillStoresZeroThresholds() throws Exception {
        TrustBadgeResult result = TrustBadgeCriteria.calculate(0, 0);

        String json = codec.writeEvidence(
                TrustBadgeType.TRUST_OWNER, result, LocalDateTime.of(2026, 8, 1, 0, 0));
        JsonNode node = objectMapper.readTree(json);

        assertEquals(0, node.path("level").asInt());
        assertEquals(0, node.path("thresholdCount").asInt());
        assertEquals(0, node.path("thresholdPercent").asInt());
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}