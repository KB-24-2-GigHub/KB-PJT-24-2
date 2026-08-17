package com.gighub.settlement.service.command;

import com.gighub.member.domain.UserRole;
import lombok.Builder;
import lombok.Getter;

/** 인증 사용자와 신고 내용을 묶은 분쟁 생성 명령입니다. */
@Getter
@Builder
public class DisputeCreateCommand {
    private final Long workCaseId;
    private final Long requesterUserId;
    private final UserRole requesterRole;
    private final String title;
    private final String content;
}
