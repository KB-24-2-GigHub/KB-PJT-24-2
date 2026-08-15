package com.gighub.document.dto;

import lombok.Getter;

/**
 * 보건증 공유 생성 성공 응답입니다.
 *
 * <p>승인 계약이 고정한 {@code shareId} 하나만 돌려줍니다. 파생된 {@code workCaseId}와 OWNER
 * 식별자는 넣지 않습니다. 공유 현황은 뒤이은 공유 이력 조회가 계산된 상태와 함께
 * 돌려줍니다.</p>
 */
@Getter
public final class HealthCertificateShareResponse {

    private final Long shareId;

    private HealthCertificateShareResponse(Long shareId) {
        this.shareId = shareId;
    }

    public static HealthCertificateShareResponse of(Long shareId) {
        return new HealthCertificateShareResponse(shareId);
    }
}
