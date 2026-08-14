package com.gighub.document.mapper;

import com.gighub.document.mapper.param.DocumentInsertParam;
import com.gighub.document.mapper.param.DocumentShareInsertParam;
import com.gighub.document.mapper.param.DocumentSignatureInsertParam;
import com.gighub.document.mapper.param.DocumentVersionInsertParam;
import com.gighub.document.mapper.result.ContractVersionPromotionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code documents} 계열 행을 만드는 {@code documents}·{@code document_versions}의 단일
 * writer입니다(MODULE_BOUNDARIES.md). 근로계약서 자동 생성({@link com.gighub.contract.ContractArtifactPort})과
 * 보건증 등록({@link com.gighub.document.service.HealthCertificateRegisterTransaction})이
 * 함께 쓰며, {@code findPromotionRowsByWorkCaseId}처럼 계약서 전용인 조회만 예외다. 조회용
 * API가 쓰는 {@link DocumentQueryMapper}, {@link DocumentAccessMapper}와는 관심사가 달라
 * 나눈다.
 */
@Mapper
public interface ContractDocumentWriteMapper {

    /** {@code documents} 한 행을 저장하고 생성된 식별자를 {@code param.id}에 채운다. */
    int insertDocument(DocumentInsertParam param);

    /** {@code document_versions} 한 행을 저장하고 생성된 식별자를 {@code param.id}에 채운다. */
    int insertVersion(DocumentVersionInsertParam param);

    int insertSignature(DocumentSignatureInsertParam param);

    int insertShare(DocumentShareInsertParam param);

    /** 문서 상태를 예상 상태에서만 전이한다. */
    int updateDocumentStatus(
            @Param("documentId") long documentId,
            @Param("expectedStatus") String expectedStatus,
            @Param("status") String status);

    /**
     * 소유 WORKER의 ACTIVE 보건증 발급일·만료일만 바꾼다(DOC-006). 파일·Version은 건드리지
     * 않는다. 대상이 없거나(존재하지 않음, 비소유, 다른 문서 유형, 이미 삭제됨) 이미 삭제된
     * 경우 0을 돌려준다.
     */
    int updateHealthCertificateIssuedDate(
            @Param("documentId") long documentId,
            @Param("ownerUserId") long ownerUserId,
            @Param("issuedOn") LocalDate issuedOn,
            @Param("expiresOn") LocalDate expiresOn);

    /**
     * 특정 근무의 EMPLOYMENT_CONTRACT 문서에 딸린 Version들의 승격 정보를 읽는다.
     *
     * <p>Commit 뒤 승격은 {@link com.gighub.contract.ContractArtifactHandle}이 저장 Key를
     * 들고 있지 않으므로 근무 식별자로 다시 조회한다.</p>
     */
    List<ContractVersionPromotionRow> findPromotionRowsByWorkCaseId(
            @Param("workCaseId") long workCaseId);
}
