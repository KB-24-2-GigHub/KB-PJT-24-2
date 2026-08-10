package com.gighub.invitation.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gighub.common.api.ApiResponse;
import com.gighub.config.ApiJsonMapper;
import com.gighub.contract.domain.ContractTermsSnapshot;
import com.gighub.invitation.application.InvitationAcceptanceResult;
import com.gighub.invitation.application.InvitationAcceptanceReplaySnapshotCodec;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 수락이 저장하는 두 JSON을 한 규칙으로 만듭니다.
 *
 * <p>계약 Snapshot과 멱등 Claim의 응답 Snapshot은 둘 다 오래 보존되고 나중에 그대로 다시
 * 쓰입니다. 운영 응답과 같은 규칙(UTC {@code Instant}, 원 단위 정수)으로 굳혀야 저장 값과
 * 다시 내보낸 값이 갈라지지 않습니다.</p>
 */
@Component
public class AcceptJson implements InvitationAcceptanceReplaySnapshotCodec {

    private final ObjectMapper objectMapper = ApiJsonMapper.create();

    /** 계약 Snapshot을 {@code work_contracts.terms_snapshot}에 넣을 문자열로 만듭니다. */
    public String writeSnapshot(ContractTermsSnapshot snapshot) {
        return write(snapshot, "계약 조건 Snapshot을 직렬화하지 못했습니다.");
    }

    /**
     * 저장된 계약 Snapshot JSON을 되돌립니다.
     *
     * <p>계약서 파일 생성이 근무 행을 다시 읽지 않고 이 Snapshot만으로 계약 내용을 복원하기
     * 위해 씁니다({@link ContractTermsSnapshot} 참고).</p>
     */
    public ContractTermsSnapshot readSnapshot(String storedJson) {
        try {
            JsonNode root = objectMapper.readTree(storedJson);
            return ContractTermsSnapshot.builder()
                    .schemaVersion(root.get("schemaVersion").asInt())
                    .termsVersion(root.get("termsVersion").asInt())
                    .title(root.get("title").asText())
                    .startsAt(Instant.parse(root.get("startsAt").asText()))
                    .endsAt(Instant.parse(root.get("endsAt").asText()))
                    .breakMinutes(root.get("breakMinutes").asInt())
                    .breakPaid(root.get("breakPaid").asBoolean())
                    .workplaceName(root.get("workplaceName").asText())
                    .workplaceAddress(root.get("workplaceAddress").asText())
                    .workplaceLatitude(decimalOrNull(root.get("workplaceLatitude")))
                    .workplaceLongitude(decimalOrNull(root.get("workplaceLongitude")))
                    .allowedRadiusMeters(decimalOrNull(root.get("allowedRadiusMeters")))
                    .dailyWage(root.get("dailyWage").asLong())
                    .owner(root.get("owner").get("userId").asLong(), root.get("owner").get("name").asText())
                    .worker(root.get("worker").get("userId").asLong(), root.get("worker").get("name").asText())
                    .build();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("저장된 계약 조건 Snapshot을 읽지 못했습니다.", exception);
        }
    }

    private static BigDecimal decimalOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : new BigDecimal(node.asText());
    }

    /** 최초 성공 응답 전체를 Claim에 저장할 문자열로 만듭니다. */
    @Override
    public String writeResponseBody(InvitationAcceptanceResult result) {
        return write(ApiResponse.of(result), "수락 응답을 직렬화하지 못했습니다.");
    }

    /**
     * 저장된 응답 JSON에서 {@code data}를 읽어 되돌립니다.
     *
     * <p>Replay는 현재 도메인 상태를 다시 보지 않고 저장한 값만 씁니다.</p>
     */
    @Override
    public InvitationAcceptanceResult readResponseBody(String storedBody) {
        try {
            JsonNode data = objectMapper.readTree(storedBody).get("data");
            if (data == null) {
                throw new IllegalStateException("저장된 수락 응답에 data가 없습니다.");
            }
            return InvitationAcceptanceResult.of(
                    data.get("workCaseId").asLong(),
                    data.get("escrowStatus").asText()
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("저장된 수락 응답을 읽지 못했습니다.", exception);
        }
    }

    private String write(Object value, String failureMessage) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            // 직렬화 실패를 삼키면 계약 Snapshot이 빈 값으로 저장될 수 있습니다.
            throw new IllegalStateException(failureMessage, exception);
        }
    }
}
