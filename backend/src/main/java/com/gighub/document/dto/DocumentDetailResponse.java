package com.gighub.document.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.Getter;

import java.util.List;
import java.util.Objects;

/** 목록과 같은 문서 Item에 안전한 Version 목록만 추가한 상세 응답입니다. */
@Getter
public final class DocumentDetailResponse {

    @JsonUnwrapped
    private final DocumentListItem item;
    private final List<DocumentVersionItem> versions;

    private DocumentDetailResponse(
            DocumentListItem item,
            List<DocumentVersionItem> versions) {
        this.item = Objects.requireNonNull(item, "item");
        this.versions = List.copyOf(Objects.requireNonNull(versions, "versions"));
    }

    public static DocumentDetailResponse of(
            DocumentListItem item,
            List<DocumentVersionItem> versions) {
        return new DocumentDetailResponse(item, versions);
    }
}
