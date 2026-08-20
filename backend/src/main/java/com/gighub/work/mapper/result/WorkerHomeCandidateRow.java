package com.gighub.work.mapper.result;

import java.time.LocalDateTime;

import com.gighub.work.domain.WorkCaseStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * WORKER 홈의 오늘 근무 후보 한 건입니다.
 *
 * <p>{@code hourlyWage}가 저장되지 않아(WORK-008 재구현 전) {@code expectedDeductionAmount},
 * {@code expectedPaymentAmount}는 이 Row가 담지 않습니다. 두 필드는 담당 기능 이슈가 없는
 * Blocked 항목입니다({@code SPEC_TRACEABILITY.md} 5B-3·5B-4).</p>
 *
 * <p>MyBatis가 &lt;constructor&gt; 매핑으로 생성하므로 필드 선언 순서가 곧 생성자 인자
 * 순서입니다. XML과 함께 바꿔야 합니다.</p>
 */
@Getter
@Builder(toBuilder = true)
@AllArgsConstructor
public class WorkerHomeCandidateRow {

    private final Long workCaseId;
    private final String title;
    private final String workplaceName;
    private final LocalDateTime startsAt;
    private final LocalDateTime endsAt;
    private final Integer breakMinutes;
    private final Boolean breakPaid;
    private final Long dailyWage;
    private final WorkCaseStatus status;
    private final LocalDateTime checkedInAt;
    private final LocalDateTime checkInAttemptedAt;
    private final LocalDateTime checkedOutAt;
    private final String escrowStatus;
    private final String settlementStatus;
    private final LocalDateTime settlementDueAt;
    private final Long workerPaidAmount;
}
