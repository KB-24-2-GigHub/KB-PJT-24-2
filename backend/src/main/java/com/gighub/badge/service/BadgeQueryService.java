package com.gighub.badge.service;

import com.gighub.badge.dto.UserBadgeListResponse;

/** Badge 조회 정책을 Controller와 persistence 사이에서 적용합니다. */
public interface BadgeQueryService {

    UserBadgeListResponse findByUserId(long userId);
}
