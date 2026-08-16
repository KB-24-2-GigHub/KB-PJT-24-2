package com.gighub.badge.service;

import java.util.Optional;

import com.gighub.badge.service.result.BadgeCalculationResult;
import com.gighub.badge.service.result.BadgeSnapshot;

/**
 * Member/Badge가 공개하는 배지 재계산·조회 경계입니다.
 *
 * <p>{@code GET /api/users/me/badge}와 초대 발급({@code InvitationIssueServiceImpl})이
 * {@link #recalculate(long)}를 호출해 사용자 행을 잠그고 다시 계산·Upsert합니다. 역할은
 * 호출자가 넘기지 않고, 잠근 사용자 행의 실제 role로 판단합니다.</p>
 *
 * <p>{@link #currentBadge(long)}는 잠금 없이 마지막으로 저장된 값만 읽습니다. 인증된 초대
 * 조회({@code InvitationQueryServiceImpl})가 이 메서드를 씁니다 — 초대를 여는 것은 단순 열람이라
 * 매번 재계산·Upsert를 걸면 같은 OWNER의 여러 초대를 동시에 여는 WORKER들이 사용자 행 잠금 하나를
 * 두고 줄을 서게 됩니다. 배지는 초대를 발급한 시점 기준으로 고정해 보여줘도 충분하다는 판단입니다.</p>
 */
public interface BadgeApplicationService {

    BadgeCalculationResult recalculate(long userId);

    Optional<BadgeSnapshot> currentBadge(long userId);
}
