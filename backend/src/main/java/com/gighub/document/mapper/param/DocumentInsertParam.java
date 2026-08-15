package com.gighub.document.mapper.param;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** {@code documents} 삽입 Parameter입니다. */
@Getter
@Builder
public class DocumentInsertParam {

    /** MyBatis가 생성 Key를 되돌려 쓰기 위해 이 필드만 가변입니다. */
    @Setter
    private Long id;

    private final Long createdByUserId;
    private final Long ownerUserId;
    private final Long workCaseId;
    private final String documentType;
    private final String status;
    private final LocalDate issuedOn;
    private final LocalDate expiresOn;
}
