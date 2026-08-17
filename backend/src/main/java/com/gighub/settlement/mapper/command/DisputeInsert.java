package com.gighub.settlement.mapper.command;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/** 생성 Key를 받아오기 위한 분쟁 INSERT 전용 모델입니다. */
@Getter
@Builder
public class DisputeInsert {
    @Setter
    private Long reportId;
    private final Long workCaseId;
    private final Long requesterUserId;
    private final String title;
    private final String content;
}
