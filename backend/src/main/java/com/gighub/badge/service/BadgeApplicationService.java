package com.gighub.badge.service;

import com.gighub.badge.service.result.BadgeCalculationResult;

/**
 * Member/Badge가 공개하는 배지 재계산 경계입니다.
 *
 * <p>{@code GET /api/users/me/badge}와 인증된 초대 조회의 OWNER 배지가 이 메서드 하나를
 * 공용으로 호출합니다. 역할은 호출자가 넘기지 않고, 잠근 사용자 행의 실제 role로 판단합니다.</p>
 */
public interface BadgeApplicationService {

    BadgeCalculationResult recalculate(long userId);
}
