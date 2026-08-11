package com.gighub.document.dto;

import lombok.Getter;

import java.util.List;
import java.util.Objects;

/**
 * 문서 공유 현황 목록 응답입니다.
 *
 * <p>공유 대상 사용자 ID와 문서 내부 ID는 Item에서 제외하며, 소유자가 공유 이력을 확인하는 데
 * 필요한 공개 식별자와 계산 상태만 반환합니다.</p>
 *
 * <p>공개 Builder를 두면 {@code of()}의 방어 복사를 건너뛰고 호출자가 들고 있는 가변
 * 목록을 그대로 담을 수 있으므로, {@code PageResponse}처럼 생성자에서 복사하고 생성 경로를
 * {@code of()} 하나로 둡니다.</p>
 */
@Getter
public final class DocumentShareListResponse {

    private final List<DocumentShareItem> items;

    private DocumentShareListResponse(List<DocumentShareItem> items) {
        this.items = List.copyOf(Objects.requireNonNull(items, "items"));
    }

    public static DocumentShareListResponse of(List<DocumentShareItem> items) {
        return new DocumentShareListResponse(items);
    }
}
