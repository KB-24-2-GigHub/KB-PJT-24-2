package com.gighub.member.service;

import com.gighub.member.service.result.MemberIdentitySnapshot;

/** 타 모듈이 계정 persistence 타입 없이 당사자 표시 정보를 읽는 공개 경계입니다. */
public interface MemberIdentityQueryService {

    MemberIdentitySnapshot findById(long userId);
}
