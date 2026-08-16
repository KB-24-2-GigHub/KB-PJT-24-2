package com.gighub.member.mapper;

import com.gighub.member.domain.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {

    int countByLoginId(@Param("loginId") String loginId);

    int countByEmail(@Param("email") String email);

    User findByLoginId(@Param("loginId") String loginId);

    User findById(@Param("userId") Long userId);

    User findProfileById(@Param("userId") Long userId);

    User lockById(@Param("userId") Long userId);

    int updatePhone(@Param("userId") Long userId, @Param("phone") String phone);

    int insert(User user);
}
