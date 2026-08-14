package com.gighub.document.contract;

import java.time.LocalDateTime;

/**
 * 근로계약서 PDF를 만드는 데 필요한 {@code work_contracts} Snapshot 값입니다.
 *
 * <p>초대 수락 Aggregate가 {@code work_contracts} Row를 만든 뒤 이 값으로 변환해
 * {@link ContractPdfRenderer}에 넘깁니다. Renderer는 이 불변 값만 사용하므로 현재
 * Work Case의 변경 가능한 필드를 다시 읽지 않습니다.</p>
 *
 * <p>{@code employerPhone}·{@code workerPhone}은 {@code users.phone}이 선택 입력이라
 * 비어 있을 수 있습니다. 이 Record는 렌더링 전용 값 전달체이며 {@code work_contracts.terms_snapshot}의
 * 승인된 JSON Shape와는 별개라, 연락처처럼 저장하지 않고 매번 다시 읽는 값을 자유롭게 더할 수
 * 있습니다.</p>
 */
public record ContractSnapshot(
        Long workCaseId,
        String title,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        int breakMinutes,
        boolean breakPaid,
        String workplaceName,
        String workplaceAddress,
        long agreedWage,
        String employerName,
        String employerPhone,
        String workerName,
        String workerPhone,
        int sourceTermsVersion,
        LocalDateTime acceptedAt) {

    /** SIGNED Version에만 붙는 서명 증거입니다(TYPED_NAME). */
    public record Signature(String typedName, LocalDateTime signedAt) {
    }
}
