package com.gighub.attendance.domain;

import com.gighub.attendance.dto.AttendanceScanResult;

import lombok.Builder;
import lombok.Getter;

/**
 * WORKER QR 스캔 한 건을 판정한 결과입니다.
 *
 * <p>판정은 두 갈래로 끝납니다. 완성된 응답을 돌려주는 성공(기록 또는 조기 퇴근 확인 요청),
 * 그리고 감사 기록을 남긴 거절입니다. 거절을 예외가 아니라 결과로 돌려주는 이유는 거절 감사
 * 행이 같은 Transaction에서 commit 되어야 하기 때문입니다. 예외로 빠져나가면 남기려던
 * 기록까지 사라집니다.</p>
 *
 * <p>성공 응답은 {@link com.gighub.attendance.service.AttendanceScanExecutor}가 멱등 Claim을
 * 완료하는 순간과 같은 Transaction에서 만듭니다. 완성된 {@link AttendanceScanResult}를 그대로
 * 담아 두면, Transaction 밖의 호출부가 같은 값을 다시 조립할 필요가 없습니다.</p>
 */
@Getter
@Builder(access = lombok.AccessLevel.PRIVATE)
public class AttendanceScanOutcome {

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
