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

    int updatePassword(@Param("userId") Long userId, @Param("passwordHash") String passwordHash);

    /**
     * ACTIVE 사용자만 탈퇴 상태로 바꿉니다.
     *
     * <p>{@code status = 'ACTIVE'} 조건이 동시 탈퇴 요청의 유일한 방어선입니다. 호출자가
     * 조회에서 이미 ACTIVE를 확인했더라도, 확인과 갱신 사이에 다른 요청이 먼저 탈퇴시키면
     * 갱신 건수가 0이 되어 두 번째 요청이 성공으로 끝나지 않습니다.</p>
     *
     * @return 갱신된 행 수. 이미 탈퇴했거나 ACTIVE가 아니면 0
     */
    int withdraw(@Param("userId") Long userId);

    int insert(User user);
}
