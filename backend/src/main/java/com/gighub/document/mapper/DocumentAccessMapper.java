package com.gighub.document.mapper;

import com.gighub.document.mapper.param.DocumentAccessLogParam;
import com.gighub.document.mapper.result.DocumentFileAccessRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;

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

    /** 철회·만료로 사라진 접근과 처음부터 권한 없는 접근의 감사 사유를 구분합니다. */
    boolean hasHealthShareHistory(
            @Param("documentId") Long documentId,
            @Param("userId") Long userId);

    int insertAccessLog(DocumentAccessLogParam param);
}
