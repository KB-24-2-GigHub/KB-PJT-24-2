package com.gighub.document.dto;

import com.gighub.document.mapper.result.DocumentListRow;

/** {@code DocumentListRow} 조회 결과를 공개 목록 Item으로 바꾸는 공용 변환입니다. */
public final class DocumentListItems {

    private DocumentListItems() {
    }

    public static DocumentListItem from(DocumentListRow row) {
        return DocumentListItem.of(
                row.getDocumentId(),
                row.getDocType(),
                row.getStatus(),
                row.getMimeType(),
                row.getIssuedDate(),
                row.getExpiresDate(),
                row.getLatestVersion(),
                row.getSource(),
                row.getOwnerName(),
                row.getSharedByName(),
                row.getWorkplaceId(),
                row.getWorkplaceName(),
                row.getWorkCaseId(),
                row.getWorkerName(),
                Boolean.TRUE.equals(row.getCanShare()),
                row.getCreatedAt());
    }
}
