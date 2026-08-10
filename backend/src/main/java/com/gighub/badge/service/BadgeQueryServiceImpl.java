package com.gighub.badge.service;

import com.gighub.badge.dto.UserBadgeListResponse;
import com.gighub.badge.mapper.BadgeQueryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Badge read model을 소유 Mapper에서 조회합니다. */
@Service
public class BadgeQueryServiceImpl implements BadgeQueryService {

    private final BadgeQueryMapper badgeQueryMapper;

    public BadgeQueryServiceImpl(BadgeQueryMapper badgeQueryMapper) {
        this.badgeQueryMapper = badgeQueryMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public UserBadgeListResponse findByUserId(long userId) {
        return UserBadgeListResponse.of(badgeQueryMapper.findBadgesByUserId(userId));
    }
}
