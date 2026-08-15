package com.gighub.document.mapper.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 삭제 전 잠금 조회가 돌려주는 소유 문서의 유형·상태입니다. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentOwnershipRow {
    private String documentType;
    private String status;
}
