package com.gighub.invitation.dto;

import lombok.Getter;

/**
 * 초대한 OWNER의 현재 승인 Badge입니다.
 *
 * <p>SPEC-484-01부터는 0단계(아직 이력 없음)도 {@code null}로 감추지 않고 이 객체를
 * 그대로 채워 반환합니다({@code level: 0}). {@code worker.badge}({@code WorkCaseDetailResponse},
 * #472)와 같은 관례입니다 — WORKER가 초대를 확인하는 시점에도 "아직 이력 쌓는 중"을 배지
 * 그림으로 보여줍니다.</p>
 */
@Getter
public final class OwnerBadgeResponse {

    private final String badgeType;
    private final Integer level;

    private OwnerBadgeResponse(String badgeType, Integer level) {
        this.badgeType = badgeType;
        this.level = level;
    }

    public static OwnerBadgeResponse of(String badgeType, Integer level) {
        return new OwnerBadgeResponse(badgeType, level);
    }
}
