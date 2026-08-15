package com.gighub.document.mapper.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 보건증을 공유할 수 있는 근무 관계 후보 한 행입니다.
 *
 * <p>Client는 {@code workplaceId}만 보내고 {@code workCaseId}와 {@code ownerUserId}는 서버가
 * 이 조회로 파생합니다(DEC-DOCUMENT-SHARE-UNIT). 두 값을 요청에서 받으면 관계 없는 근무나
 * 사용자로 공유 행을 만들 수 있습니다.</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShareCandidateRow {

    private Long workCaseId;
    private Long ownerUserId;
}
