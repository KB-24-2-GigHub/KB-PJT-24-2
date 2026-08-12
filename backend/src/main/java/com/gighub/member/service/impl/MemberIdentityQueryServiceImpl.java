package com.gighub.member.service.impl;

import com.gighub.member.domain.User;
import com.gighub.member.mapper.UserMapper;
import com.gighub.member.service.MemberIdentityQueryService;
import com.gighub.member.service.result.MemberIdentitySnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberIdentityQueryServiceImpl implements MemberIdentityQueryService {

    private final UserMapper userMapper;

    @Override
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public MemberIdentitySnapshot findById(long userId) {
        User user = userMapper.findById(userId);
        if (user == null || user.getId() == null || user.getName() == null) {
            return null;
        }
        return new MemberIdentitySnapshot(user.getId(), user.getName());
    }
}
