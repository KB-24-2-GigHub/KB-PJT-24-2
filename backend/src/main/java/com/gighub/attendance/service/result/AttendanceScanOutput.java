package com.gighub.attendance.service.result;

import com.gighub.attendance.dto.AttendanceScanResponse;

/**
 * 스캔 응답과 그 응답이 최초 처리인지 Replay인지를 함께 전달합니다.
 *
 * <p>Controller가 {@code Idempotency-Replayed} Header를 붙일지 정하려면 이 구분이
 * 필요합니다. 응답 본문만으로는 둘을 구별할 수 없습니다.</p>
 */
public record AttendanceScanOutput(AttendanceScanResponse response, boolean replayed) {

    public static AttendanceScanOutput first(AttendanceScanResponse response) {
        return new AttendanceScanOutput(response, false);
    }

    public static AttendanceScanOutput replayed(AttendanceScanResponse response) {
        return new AttendanceScanOutput(response, true);
    }
}
