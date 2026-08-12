package com.gighub.attendance.service.result;

import com.gighub.attendance.domain.AttendanceFailureReason;
import com.gighub.attendance.dto.AttendanceScanResult;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;

/**
 * 스캔 트랜잭션이 Commit한 성공 응답 또는 감사된 거절 사유를 Application 경계로 전달합니다.
 *
 * <p>HTTP 응답 DTO를 보관하는 이 타입은 Domain 정책이 아니라 트랜잭션 조정 결과입니다.
 * Domain이 Web DTO에 의존하지 않도록 service result에 둡니다.</p>
 */
@Getter
@Builder(access = AccessLevel.PRIVATE)
public final class AttendanceScanOutcome {

    private final AttendanceScanResult response;
    private final AttendanceFailureReason failureReason;

    public static AttendanceScanOutcome success(AttendanceScanResult response) {
        return AttendanceScanOutcome.builder().response(response).build();
    }

    public static AttendanceScanOutcome rejected(AttendanceFailureReason reason) {
        return AttendanceScanOutcome.builder().failureReason(reason).build();
    }

    public boolean isRejected() {
        return failureReason != null;
    }
}
