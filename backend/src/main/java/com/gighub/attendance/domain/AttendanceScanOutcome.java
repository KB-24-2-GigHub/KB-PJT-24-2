package com.gighub.attendance.domain;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Getter;

/**
 * WORKER QR 스캔 한 건을 판정한 결과입니다.
 *
 * <p>판정은 세 갈래로 끝납니다. 기록 성공, 조기 퇴근 확인 요청, 그리고 감사 기록을 남긴
 * 거절입니다. 거절을 예외가 아니라 결과로 돌려주는 이유는 거절 감사 행이 같은 Transaction
 * 에서 commit 되어야 하기 때문입니다. 예외로 빠져나가면 남기려던 기록까지 사라집니다.</p>
 *
 * <p>검증과 전이가 모두 끝난 뒤 완성된 값만 Builder로 한 번에 만듭니다. Setter로 부분
 * 조립하지 않습니다.</p>
 */
@Getter
@Builder(access = lombok.AccessLevel.PRIVATE)
public class AttendanceScanOutcome {

    private final AttendanceType scanType;
    private final Long workCaseId;
    private final LocalDateTime recordedAt;
    private final LocalDateTime earlyCheckoutConfirmedAt;
    private final LocalDateTime settlementDueAt;
    private final LocalDateTime scheduledEndAt;
    private final boolean late;
    private final Integer lateMinutes;
    private final boolean confirmationRequired;
    private final AttendanceFailureReason failureReason;

    public static AttendanceScanOutcome checkedIn(
            long workCaseId, LocalDateTime recordedAt, boolean late, int lateMinutes) {
        return AttendanceScanOutcome.builder()
                .workCaseId(workCaseId)
                .scanType(AttendanceType.CHECK_IN)
                .recordedAt(recordedAt)
                .late(late)
                .lateMinutes(lateMinutes)
                .build();
    }

    public static AttendanceScanOutcome checkedOut(
            long workCaseId,
            LocalDateTime recordedAt,
            LocalDateTime earlyCheckoutConfirmedAt,
            LocalDateTime settlementDueAt) {
        return AttendanceScanOutcome.builder()
                .workCaseId(workCaseId)
                .scanType(AttendanceType.CHECK_OUT)
                .recordedAt(recordedAt)
                .earlyCheckoutConfirmedAt(earlyCheckoutConfirmedAt)
                .settlementDueAt(settlementDueAt)
                .build();
    }

    public static AttendanceScanOutcome confirmationRequired(
            long workCaseId, LocalDateTime scheduledEndAt) {
        return AttendanceScanOutcome.builder()
                .workCaseId(workCaseId)
                .scanType(AttendanceType.CHECK_OUT)
                .scheduledEndAt(scheduledEndAt)
                .confirmationRequired(true)
                .build();
    }

    public static AttendanceScanOutcome rejected(
            long workCaseId, AttendanceFailureReason reason) {
        return AttendanceScanOutcome.builder()
                .workCaseId(workCaseId)
                .failureReason(reason)
                .build();
    }

    public boolean isRejected() {
        return failureReason != null;
    }
}
