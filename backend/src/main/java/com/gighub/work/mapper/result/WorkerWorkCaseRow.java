package com.gighub.work.mapper.result;

import java.time.LocalDateTime;

import com.gighub.work.domain.WorkCaseStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * WORKER 근무 이력 목록 한 Page Item의 원본 행입니다.
 *
 * <p>WORK-007이 요구하는 근무 조건·성공 근태(파생 지각의 재료)·에스크로·정산 상태를 한 행에
 * 담습니다. 정밀 좌표, QR Token, OWNER 잔액과 계약 Storage 정보는 SELECT 자체에 넣지
 * 않습니다.</p>
 *
 * <p>MyBatis가 &lt;constructor&gt; 매핑으로 생성하므로 필드 선언 순서가 곧 생성자 인자
 * 순서입니다. XML과 함께 바꿔야 합니다.</p>
 */
@Getter
@Builder(toBuilder = true)
@AllArgsConstructor
public class WorkerWorkCaseRow {

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
}
