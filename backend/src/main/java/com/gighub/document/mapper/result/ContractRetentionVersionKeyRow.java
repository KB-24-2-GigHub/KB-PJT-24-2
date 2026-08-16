package com.gighub.document.mapper.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 보존 만료 파기 대상 근로계약서 한 Version의 Storage Key 정보입니다(DOC-012). */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractRetentionVersionKeyRow {
    private Long versionId;
    private Long workCaseId;
    private Integer versionNo;
    private String storageKey;
}
