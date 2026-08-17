package com.gighub.document.mapper;

import com.gighub.document.mapper.result.DocumentListRow;
import com.gighub.document.mapper.result.DocumentShareRow;
import com.gighub.document.mapper.result.ShareCandidateRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DocumentQueryMapper {

    /**
     * 공유 알림 문구에 쓸 근무 제목입니다.
     *
     * <p>QX-003이 이미 이 Mapper의 {@code work_cases} 읽기를 허용합니다. 알림 문구 하나를
     * 위해 Work 모듈에 새 공개 Query를 만들지 않습니다.</p>
     */
    String findWorkCaseTitle(@Param("workCaseId") Long workCaseId);
    List<DocumentListRow> findDocuments(
            @Param("userId") Long userId,
            @Param("role") String role,
            @Param("workplaceId") Long workplaceId,
            @Param("docType") String docType,
            @Param("now") LocalDateTime now,
            @Param("today") LocalDate today,
            @Param("offset") long offset,
            @Param("size") int size);

    long countDocuments(
            @Param("userId") Long userId,
            @Param("role") String role,
            @Param("workplaceId") Long workplaceId,
            @Param("docType") String docType,
            @Param("now") LocalDateTime now,
            @Param("today") LocalDate today);

    boolean isOwnedActiveHealthDocument(
            @Param("documentId") Long documentId,
            @Param("ownerUserId") Long ownerUserId);

    /** 등록·수정 직후 재조회용입니다. 없거나 소유자가 아니면 {@code null}입니다. */
    DocumentListRow findOwnHealthCertificateById(
            @Param("userId") Long userId,
            @Param("documentId") Long documentId,
            @Param("today") LocalDate today);

    List<DocumentShareRow> findSharesByDocumentId(
            @Param("documentId") Long documentId,
            @Param("now") LocalDateTime now,
            @Param("today") LocalDate today,
            @Param("offset") long offset,
            @Param("size") int size);

    long countSharesByDocumentId(@Param("documentId") Long documentId);

    /**
     * 공유 대상이 될 수 있는 소유 보건증의 만료일을 읽습니다.
     *
     * <p>{@code null}은 문서가 없거나 삭제됐거나 소유자가 아니거나 보건증이 아니라는 뜻이며
     * 모두 404로 존재를 숨깁니다(DEC-DOCUMENT-ERROR-CATALOG). 만료 자체는 여기서 거르지 않고
     * 날짜를 돌려주는데, 만료는 404가 아니라 {@code workplaceId} 필드 오류의 400이라 호출자가
     * 두 경우를 구분해야 하기 때문입니다.</p>
     */
    LocalDate findOwnActiveHealthCertificateExpiry(
            @Param("documentId") Long documentId, @Param("ownerUserId") Long ownerUserId);

    /**
     * 한 사업장에서 인증 WORKER가 보건증을 공유할 수 있는 근무 관계를 모두 읽습니다.
     *
     * <p>후보는 ACTIVE 사업장의 {@code ACCEPTED}·{@code READY} Work Case입니다. 결과가
     * 비면 사업장 없음·비활성·후보 없음을 구분하지 않고 모두 {@code workplaceId} 필드 오류의
     * 400이고, 둘 이상이면 서버가 임의로 고르지 않고 409입니다(DEC-DOCUMENT-SHARE-UNIT).</p>
     */
    List<ShareCandidateRow> findShareCandidates(
            @Param("workerId") Long workerId, @Param("workplaceId") Long workplaceId);
}
