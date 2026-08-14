package com.gighub.document.contract;

import java.time.Duration;
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
 * 승인된 JSON Shape와는 별개라, 연락처처럼 저장하지 않고 수락 Aggregate가 확정한 시점의 값을
 * 자유롭게 더할 수 있습니다.</p>
 *
 * <p>{@code employerActionAt}은 사업주 서명란에 표시하는 근무 등록 일시({@code work_cases.created_at})
 * 입니다. 사업주는 별도 서명 절차 없이 근무 조건을 등록한 시점을 계약 제시 시점으로 봅니다.</p>
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
        LocalDateTime acceptedAt,
        LocalDateTime employerActionAt) {

    public ContractSnapshot {
        long shiftMinutes = Duration.between(startsAt, endsAt).toMinutes();
        if (breakMinutes < 0 || breakMinutes > shiftMinutes) {
            throw new IllegalArgumentException(
                    "휴게시간(" + breakMinutes + "분)이 근무시간(" + shiftMinutes + "분)을 벗어났습니다.");
        }
    }

    /** SIGNED Version에만 붙는 서명 증거입니다(TYPED_NAME). */
    public record Signature(String typedName, LocalDateTime signedAt) {
    }
}
