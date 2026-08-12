package com.gighub.badge.mapper.result;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** {@code user_badges} 조회 결과를 persistence 경계 안에서 전달합니다. */
@Getter
@AllArgsConstructor
public final class UserBadgeRow {

    private final Long id;
    private final Long userId;
    private final String badgeType;
    private final String evidence;
    private final LocalDateTime awardedAt;
}
