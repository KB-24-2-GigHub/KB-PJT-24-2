package com.gighub.badge.service;

import com.gighub.badge.dto.UserBadge;
import com.gighub.badge.dto.UserBadgeListResponse;
import com.gighub.badge.mapper.BadgeQueryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Badge read model을 소유 Mapper에서 조회합니다. */
@Service
@RequiredArgsConstructor
public class BadgeQueryServiceImpl implements BadgeQueryService {

    private final BadgeQueryMapper badgeQueryMapper;

    @Override
    @Transactional(readOnly = true)
    public UserBadgeListResponse findByUserId(long userId) {
        return UserBadgeListResponse.of(badgeQueryMapper.findBadgesByUserId(userId).stream()
                // 이 리팩터링은 외부 Shape를 바꾸지 않고 Row와 API 타입의 결합만 끊습니다.
                .map(row -> UserBadge.of(
                        row.getId(),
                        row.getUserId(),
                        row.getBadgeType(),
                        row.getEvidence(),
                        row.getAwardedAt()))
                .toList());
    }
}
