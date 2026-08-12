package com.gighub.document.mapper;

import com.gighub.document.mapper.param.DocumentAccessLogParam;
import com.gighub.document.mapper.result.DocumentFileAccessRow;
import com.gighub.document.mapper.result.DocumentHealthShareAccessRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 당사자 파일 접근 권한 검사와 접근 감사 기록을 담당합니다(DOC-011). */
@Mapper
public interface DocumentAccessMapper {

    /** 문서와 허용 Version을 잠가 파일 접근의 선형화 지점을 만듭니다. */
    DocumentFileAccessRow lockFileAccessContext(@Param("documentId") Long documentId);

    /** 현재도 유효한 보건증 공유 한 건을 잠급니다. */
    Long lockValidHealthShare(
            @Param("documentId") Long documentId,
            @Param("userId") Long userId,
            @Param("now") LocalDateTime now,
            @Param("today") LocalDate today);

    /** 단건 상세가 지정한 Work Case와 일치하는 유효 보건증 공유 행을 모두 잠급니다. */
    List<DocumentHealthShareAccessRow> lockValidHealthShareContexts(
            @Param("documentId") Long documentId,
            @Param("userId") Long userId,
            @Param("workCaseId") Long workCaseId,
            @Param("now") LocalDateTime now,
            @Param("today") LocalDate today);

    /** 철회·만료로 사라진 접근과 처음부터 권한 없는 접근의 감사 사유를 구분합니다. */
    boolean hasHealthShareHistory(
            @Param("documentId") Long documentId,
            @Param("userId") Long userId);

    /** 선택한 Work Case에 과거 공유 관계가 있었는지 거부 감사 사유 판정에 사용합니다. */
    boolean hasHealthShareHistoryForWorkCase(
            @Param("documentId") Long documentId,
            @Param("userId") Long userId,
            @Param("workCaseId") Long workCaseId);

    /** OWN 보건증에 아직 공유 가능한 근무가 있는지 Capability 계산에만 사용합니다. */
    boolean hasShareableHealthWorkCase(
            @Param("documentId") Long documentId,
            @Param("ownerUserId") Long ownerUserId,
            @Param("now") LocalDateTime now,
            @Param("today") LocalDate today);

    int insertAccessLog(DocumentAccessLogParam param);
}
