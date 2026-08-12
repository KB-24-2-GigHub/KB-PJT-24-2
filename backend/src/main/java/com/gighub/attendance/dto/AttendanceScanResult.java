package com.gighub.attendance.dto;

/**
 * WORKER QR 출퇴근 스캔이 반환하는 두 응답 형태의 공통 표시자입니다.
 *
 * <p>API_SPEC 6.0.0은 {@code RECORDED}와 {@code CONFIRMATION_REQUIRED}의 필드 구성을 서로
 * 다르게 고정합니다. 한 클래스에 두 형태를 모두 담고 안 쓰는 쪽을 {@code null}로 채우면
 * 계약에 없는 필드가 응답에 함께 나갑니다. 그래서 형태마다 별도 DTO를 두고, Controller와
 * 멱등 Replay Codec은 이 표시자로만 다룹니다.</p>
 */
public sealed interface AttendanceScanResult
        permits AttendanceScanResponse, AttendanceScanConfirmationResponse {

    String getResult();
}
