package com.gighub.attendance.service;

import com.gighub.attendance.dto.AttendanceScanRequest;
import com.gighub.attendance.service.result.AttendanceScanOutput;
import com.gighub.auth.security.AuthPrincipal;

/** 인증 WORKER의 사업장 고정 QR 스캔을 검증하고 근태·근무 상태를 함께 판정합니다. */
public interface AttendanceScanService {

    AttendanceScanOutput scan(
            AuthPrincipal principal, String idempotencyKey, AttendanceScanRequest request);
}
