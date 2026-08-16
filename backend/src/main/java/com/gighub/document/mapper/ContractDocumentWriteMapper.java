package com.gighub.document.mapper;

import com.gighub.document.mapper.param.DocumentInsertParam;
import com.gighub.document.mapper.param.DocumentShareInsertParam;
import com.gighub.document.mapper.param.DocumentSignatureInsertParam;
import com.gighub.document.mapper.param.DocumentVersionInsertParam;
import com.gighub.document.mapper.result.ContractRetentionCandidateRow;
import com.gighub.document.mapper.result.ContractRetentionVersionKeyRow;
import com.gighub.document.mapper.result.ContractVersionPromotionRow;
import com.gighub.document.mapper.result.DocumentOwnershipRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
     * 삭제 대상 문서를 잠그고 유형·상태를 읽는다(DOC-006). 소유자의 요청 처리 도중 다른
     * Transaction이 같은 행을 바꾸지 못하게 막는다. 없거나 비소유면 {@code null}이다.
     */
    DocumentOwnershipRow lockOwnDocument(
            @Param("documentId") long documentId, @Param("ownerUserId") long ownerUserId);

    /** ACTIVE 보건증만 논리 삭제한다. 대상이 이미 다른 상태면 0을 돌려준다. */
    int deleteHealthCertificate(@Param("documentId") long documentId);

    /** 문서의 ACTIVE 공유를 모두 REVOKED로 철회한다. 대상이 없어도 성공이다. */
    int revokeActiveShares(
            @Param("documentId") long documentId, @Param("revokedAt") LocalDateTime revokedAt);

    /**
     * 한 사업장과 연결된 문서의 ACTIVE 공유만 REVOKED로 철회한다(DOC-008). {@code work_cases}로
     * 조인해 {@code workplaceId}를 판정하므로, 같은 문서가 다른 사업장에 공유한 행은 건드리지
     * 않는다. 대상이 없어도 0을 돌려주며 이는 오류가 아니다(멱등 철회).
     */
    int revokeActiveSharesByWorkplace(
            @Param("documentId") long documentId,
            @Param("workplaceId") long workplaceId,
            @Param("revokedAt") LocalDateTime revokedAt);

    /**
     * 특정 근무의 EMPLOYMENT_CONTRACT 문서에 딸린 Version들의 승격 정보를 읽는다.
     *
     * <p>Commit 뒤 승격은 {@link com.gighub.contract.ContractArtifactHandle}이 저장 Key를
     * 들고 있지 않으므로 근무 식별자로 다시 조회한다.</p>
     */
    List<ContractVersionPromotionRow> findPromotionRowsByWorkCaseId(
            @Param("workCaseId") long workCaseId);

    /**
     * {@code work_cases.ends_at}의 서울 종료 날짜에 3년을 더한 자정이 지난 근로계약서를
     * {@code documentId} 오름차순으로 {@code afterDocumentId} 초과부터 최대 {@code limit}건
     * 찾는다(DOC-012, {@code DEC-CONTRACT-RETENTION}). 이미 {@code DELETED}인 문서도 저장소
     * Object 삭제 재시도 대상이라 함께 돌려준다. {@code CANCELED} 문서는 제외한다. 호출자가
     * 이 Keyset으로 전체 Page를 순회해야 앞선 Page의 문서 때문에 뒤 대상이 굶지 않는다
     * (SPEC-178-05).
     */
    List<ContractRetentionCandidateRow> findContractRetentionCandidates(
            @Param("afterDocumentId") long afterDocumentId, @Param("limit") int limit);

    /**
     * {@code work_case_id}가 비었거나 참조 {@code work_cases} 행이 없는 근로계약서
     * 식별자를 {@code documentId} 오름차순으로 {@code afterDocumentId} 초과부터 최대
     * {@code limit}건 찾는다. 자동 생성 정책(DEC-CONTRACT-AUTO-GENERATION)상 있을 수 없는
     * 데이터 손상이며 파기하지 않고 감사만 한다. 호출자가 이 Keyset으로 전체 Page를
     * 순회해야 앞선 Page의 고아 문서 때문에 뒤 대상이 굶지 않는다.
     */
    List<Long> findOrphanedContractDocumentIds(
            @Param("afterDocumentId") long afterDocumentId, @Param("limit") int limit);

    /** 근로계약서를 {@code DELETED}로 전이한다. 이미 {@code DELETED}면 0을 돌려준다(멱등). */
    int markContractDeleted(@Param("documentId") long documentId);

    /**
     * 한 문서의 모든 Version에 대해 근무 식별자·Version 번호·최종 Storage Key를 돌려준다.
     * 파기는 최종 Key뿐 아니라 {@link com.gighub.document.storage.ContractStorageKeys}로
     * 유도할 수 있는 대응 임시 Key도 함께 정리해야 하므로 재구성에 필요한 값을 모두 담는다.
     */
    List<ContractRetentionVersionKeyRow> findVersionKeysByDocumentId(
            @Param("documentId") long documentId);
}
