package com.gighub.badge.mapper;

import com.gighub.badge.mapper.param.UserBadgeUpsertParam;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code user_badges}의 유일한 허용 writer이자 소유 owner의 read 경로입니다.
 *
 * <p>{@code MODULE_BOUNDARIES.md}가 이미 이 정확한 이름({@code badge.mapper.UserBadgeMapper})을
 * 허용 writer로 지정해 두었습니다. {@code UNIQUE(user_id, badge_type)}로 재계산마다 같은 행을
 * 갱신하는 멱등 Upsert와, 잠금 없이 마지막 값만 읽는 단수 Projection Query를 제공합니다.</p>
 */
@Mapper
public interface UserBadgeMapper {

    int upsert(UserBadgeUpsertParam param);

    String findEvidenceByUserIdAndType(
            @Param("userId") long userId, @Param("badgeType") String badgeType);
}