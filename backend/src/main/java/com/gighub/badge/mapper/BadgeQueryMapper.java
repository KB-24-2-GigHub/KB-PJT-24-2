package com.gighub.badge.mapper;

import com.gighub.badge.mapper.result.UserBadgeRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BadgeQueryMapper {
    List<UserBadgeRow> findBadgesByUserId(@Param("userId") Long userId);
}
