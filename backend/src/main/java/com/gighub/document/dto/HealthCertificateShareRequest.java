package com.gighub.document.dto;

import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 보건증 공유 생성 입력입니다.
 *
 * <p>{@code workplaceId} 하나만 받습니다. Work Case와 OWNER는 서버가 근무 관계에서 파생하며
 * Client 입력을 신뢰하지 않습니다(DEC-DOCUMENT-SHARE-UNIT). 그래서 그 밖의 필드는 조용히
 * 무시하지 않고 요청 오류로 거부합니다. 무시하면 {@code workCaseId}를 함께 보낸 Client가
 * 그 값이 반영됐다고 오해합니다.</p>
 */
public final class HealthCertificateShareRequest {

    @NotNull(message = "공유할 사업장은 필수입니다.")
    private final Long workplaceId;

    @JsonCreator
    public HealthCertificateShareRequest(@JsonProperty("workplaceId") Long workplaceId) {
        this.workplaceId = workplaceId;
    }

    public Long getWorkplaceId() {
        return workplaceId;
    }

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("허용되지 않은 보건증 공유 필드입니다: " + fieldName);
    }
}
