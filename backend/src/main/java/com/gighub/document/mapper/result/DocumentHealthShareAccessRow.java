package com.gighub.document.mapper.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** 단건 상세의 보건증 SHARED 관계를 판정하는 내부 Projection입니다. */
@Getter
@Builder
@AllArgsConstructor
public class DocumentHealthShareAccessRow {

    private final Long shareId;
    private final Long workCaseId;
    private final Long workplaceId;
    private final String workplaceName;
}
