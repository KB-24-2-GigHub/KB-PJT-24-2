package com.gighub.invitation.dto;

import lombok.Getter;

import java.time.Instant;

/**
 * 인증 WORKER에게 보여 줄 초대의 근무 조건입니다.
 *
 * <p>승인 명세가 확정한 필드만 담습니다. 근무·사업장·사용자 식별자와 근무지 좌표는 초대
 * 화면에 필요하지 않으므로 두지 않습니다. 필드를 만들어 두면 이후 변경에서 조용히 채워질 수
 * 있습니다.</p>
 *
 * <p>시각은 UTC {@code Instant}, 금액은 KRW 원 단위 정수입니다. DB의 Asia/Seoul 벽시계
 * 값은 서버 경계에서 변환합니다.</p>
 */
@Getter
public final class InvitationDetailResponse {

    private final String title;
    private final String workplaceName;
    private final Instant startsAt;
    private final Instant endsAt;
    private final int breakMinutes;
    private final boolean breakPaid;
    private final long dailyWage;
    private final Instant expiresAt;
    private final OwnerBadgeResponse ownerBadge;

    private InvitationDetailResponse(
            String title,
            String workplaceName,
            Instant startsAt,
            Instant endsAt,
            int breakMinutes,
            boolean breakPaid,
            long dailyWage,
            Instant expiresAt,
            OwnerBadgeResponse ownerBadge) {
        this.title = title;
        this.workplaceName = workplaceName;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.breakMinutes = breakMinutes;
        this.breakPaid = breakPaid;
        this.dailyWage = dailyWage;
        this.expiresAt = expiresAt;
        this.ownerBadge = ownerBadge;
    }

    /**
     * @param ownerBadge SPEC-484-01부터 0단계도 채워진 객체({@code level: 0})로 넘어온다
     */
    public static InvitationDetailResponse of(
            String title,
            String workplaceName,
            Instant startsAt,
            Instant endsAt,
            int breakMinutes,
            boolean breakPaid,
            long dailyWage,
            Instant expiresAt,
            OwnerBadgeResponse ownerBadge) {
        return new InvitationDetailResponse(
                title,
                workplaceName,
                startsAt,
                endsAt,
                breakMinutes,
                breakPaid,
                dailyWage,
                expiresAt,
                ownerBadge
        );
    }
}
