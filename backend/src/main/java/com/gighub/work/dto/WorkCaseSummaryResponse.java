package com.gighub.work.dto;

import java.util.Map;

import com.gighub.work.domain.WorkCaseStatus;

import lombok.Builder;
import lombok.Getter;

/**
 * 사업장의 상태별 근무 Case 건수 요약입니다.
 *
 * <p>API_SPEC 4.0.0이 8개 상태를 한 번씩만, 데이터가 없는 상태도 Key를 생략하지 않고 0으로
 * 반환하도록 고정했습니다. 필드를 8개로 닫아 두면 새 상태가 추가돼도 이 DTO가 자동으로 값을
 * 만들어내지 않고 컴파일 오류로 드러나, 응답 계약을 명세 없이 조용히 넓히지 않습니다.</p>
 */
@Getter
@Builder
public final class WorkCaseSummaryResponse {

    private final long draft;
    private final long accepted;
    private final long ready;
    private final long inProgress;
    private final long checkOutMissing;
    private final long completed;
    private final long noShow;
    private final long canceled;

    /**
     * {@code GROUP BY} 집계 행에서 응답을 만듭니다.
     *
     * <p>DB는 건수가 1 이상인 상태만 돌려주므로, 존재하지 않는 상태는 여기서 0으로
     * 채웁니다.</p>
     */
    public static WorkCaseSummaryResponse of(Map<WorkCaseStatus, Long> counts) {
        return WorkCaseSummaryResponse.builder()
                .draft(counts.getOrDefault(WorkCaseStatus.DRAFT, 0L))
                .accepted(counts.getOrDefault(WorkCaseStatus.ACCEPTED, 0L))
                .ready(counts.getOrDefault(WorkCaseStatus.READY, 0L))
                .inProgress(counts.getOrDefault(WorkCaseStatus.IN_PROGRESS, 0L))
                .checkOutMissing(counts.getOrDefault(WorkCaseStatus.CHECK_OUT_MISSING, 0L))
                .completed(counts.getOrDefault(WorkCaseStatus.COMPLETED, 0L))
                .noShow(counts.getOrDefault(WorkCaseStatus.NO_SHOW, 0L))
                .canceled(counts.getOrDefault(WorkCaseStatus.CANCELED, 0L))
                .build();
    }
}
