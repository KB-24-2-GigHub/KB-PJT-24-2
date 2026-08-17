package com.gighub.work.mapper.result;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * 근무 상세의 계약 요약입니다.
 *
 * <p>{@code work_contracts}에는 연결 문서 식별자가 없어 {@code documents.work_case_id}와
 * {@code document_type='EMPLOYMENT_CONTRACT'}의 Unique 제약으로 조인합니다.
 * {@code documentId}가 {@code null}이면 계약은 있는데 문서 행 자체가 없는 손상 상태이므로,
 * 호출부는 행이 없는 경우와 이 경우를 반드시 구분해야 합니다. {@code documentId}가 있어도
 * {@code documentStatus}가 {@code DELETED}면 보존 만료 파기(DOC-012)로 파일이 이미 사라진
 * 상태라 손상이 아니다 — 이 경우 호출부는 조회 가능한 문서가 없는 것처럼 다뤄야 한다.</p>
 */
@Getter
@Builder(toBuilder = true)
@AllArgsConstructor
public class ContractDetailRow {

    private final Long contractId;
    private final Long documentId;
    private final String documentStatus;
    private final Integer sourceTermsVersion;
    private final LocalDateTime acceptedAt;
}
