package com.gighub.document.mapper;

import com.gighub.document.mapper.result.DocumentListRow;
import com.gighub.document.mapper.result.DocumentShareRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DocumentQueryMapper {
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

    List<DocumentShareRow> findSharesByDocumentId(
            @Param("documentId") Long documentId,
            @Param("now") LocalDateTime now,
            @Param("today") LocalDate today);
}
