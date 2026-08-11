package com.gighub.attendance.controller;

import javax.validation.Valid;

import com.gighub.attendance.dto.AttendanceScanRequest;
import com.gighub.attendance.dto.AttendanceScanResponse;
import com.gighub.attendance.service.AttendanceScanService;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.auth.security.AuthPrincipals;
import com.gighub.common.api.ApiResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 인증 WORKER의 사업장 고정 QR 출퇴근 스캔 Endpoint입니다. */
@RestController
public class AttendanceScanController {

    private final AttendanceScanService attendanceScanService;

    public AttendanceScanController(AttendanceScanService attendanceScanService) {
        this.attendanceScanService = attendanceScanService;
    }

    @PostMapping("/api/attendance/scans")
    public ResponseEntity<ApiResponse<AttendanceScanResponse>> scan(
            @Valid @RequestBody AttendanceScanRequest request,
            Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);
        return ResponseEntity.ok(
                ApiResponse.of(attendanceScanService.scan(principal, request)));
    }
}
