package com.gighub.attendance.controller;

import javax.validation.Valid;

import com.gighub.attendance.dto.AttendanceScanRequest;
import com.gighub.attendance.dto.AttendanceScanResult;
import com.gighub.attendance.service.AttendanceScanService;
import com.gighub.attendance.service.result.AttendanceScanOutput;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.auth.security.AuthPrincipals;
import com.gighub.common.api.ApiResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 인증 WORKER의 사업장 고정 QR 출퇴근 스캔 Endpoint입니다. */
@RestController
public class AttendanceScanController {

    private static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private final AttendanceScanService attendanceScanService;

    public AttendanceScanController(AttendanceScanService attendanceScanService) {
        this.attendanceScanService = attendanceScanService;
    }

    /**
     * 최초 처리와 Replay를 모두 {@code 200}으로 응답하고 Header로 구분합니다.
     *
     * <p>스캔은 자원을 새로 만드는 Operation이 아니라 근태 사실을 기록하는 Operation이라
     * 최초 성공도 {@code 201}이 아닙니다.</p>
     */
    @PostMapping("/api/attendance/scans")
    public ResponseEntity<ApiResponse<AttendanceScanResult>> scan(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AttendanceScanRequest request,
            Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);
        AttendanceScanOutput output =
                attendanceScanService.scan(principal, idempotencyKey, request);

        ApiResponse<AttendanceScanResult> body = ApiResponse.of(output.response());
        if (output.replayed()) {
            return ResponseEntity.ok().header(REPLAYED_HEADER, "true").body(body);
        }
        return ResponseEntity.ok(body);
    }
}
