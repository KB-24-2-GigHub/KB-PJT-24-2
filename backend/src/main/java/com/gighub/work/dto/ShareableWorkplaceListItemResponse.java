package com.gighub.work.dto;

import java.time.Instant;
import java.time.LocalDateTime;

import com.gighub.common.api.ApiTimes;

import lombok.Getter;

/**
 * {@code GET /api/worker/workplaces}의 Page Item 하나입니다.
 *
 * <p>승인 계약이 고정한 다섯 필드만 반환합니다. Work Case ID를 넣지 않는 것이 계약의 핵심
 * 입니다. 공유 요청은 {@code workplaceId}만 받고 서버가 Work Case와 OWNER를 결정하므로,
 * 여기서 {@code workCaseId}를 미리 내보내면 Client가 그 값을 되돌려 보내는 경로가 생깁니다
 * (DEC-DOCUMENT-SHARE-UNIT).</p>
 *
 * <p>OWNER 사용자 ID, 사업장 좌표·연락처, 근무 금액과 상태도 이 화면에 필요하지 않아
 * 반환하지 않습니다.</p>
 */
@Getter
public final class ShareableWorkplaceListItemResponse {

    private final Long workplaceId;
    private final String workplaceName;
    private final String ownerName;
    private final Instant startsAt;
    private final Instant endsAt;

    private ShareableWorkplaceListItemResponse(
            Long workplaceId,
            String workplaceName,
            String ownerName,
            LocalDateTime startsAt,
            LocalDateTime endsAt) {
        this.workplaceId = workplaceId;
        this.workplaceName = workplaceName;
        this.ownerName = ownerName;
        this.startsAt = ApiTimes.toInstant(startsAt);
        this.endsAt = ApiTimes.toInstant(endsAt);
    }

    public static ShareableWorkplaceListItemResponse of(
            Long workplaceId,
            String workplaceName,
            String ownerName,
            LocalDateTime startsAt,
            LocalDateTime endsAt) {
        return new ShareableWorkplaceListItemResponse(
                workplaceId, workplaceName, ownerName, startsAt, endsAt);
    }
}
