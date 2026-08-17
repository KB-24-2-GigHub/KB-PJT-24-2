package com.gighub.document.mapper.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 보존 만료 후보 근로계약서 한 건입니다(DOC-012). {@code status}가 이미 {@code DELETED}면
 * 상태 전이는 건너뛰고 저장소 Object 삭제만 재시도한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractRetentionCandidateRow {
    private Long documentId;
    private String status;
}
