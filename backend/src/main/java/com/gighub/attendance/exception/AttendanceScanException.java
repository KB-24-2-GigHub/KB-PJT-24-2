package com.gighub.attendance.exception;

import com.gighub.common.api.ApiErrorCode;
import com.gighub.common.exception.ApiException;

import org.springframework.http.HttpStatus;

/**
 * 근태 스캔이 반환하는 승인된 오류 계약입니다.
 *
 * <p>API_SPEC의 오류 표가 상황마다 상태와 Code를 함께 고정하고 있어, 상황별 예외 클래스를
 * 열두 개 만드는 대신 승인된 조합을 정적 Factory로 노출합니다. 새 조합을 임의로 만들 수
 * 없도록 생성자는 감춥니다.</p>
 */
public final class AttendanceScanException extends ApiException {

    private AttendanceScanException(HttpStatus status, ApiErrorCode code, String message) {
        super(status, code, message);
    }

    /** QR 형식·Key ID·HMAC·사업장 식별에 실패했습니다. */
    public static AttendanceScanException qrInvalid() {
        return new AttendanceScanException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.QR_INVALID,
                "QR 코드를 확인할 수 없습니다.");
    }

    /** 서명은 유효하지만 현재 활성 QR이 아닙니다. */
    public static AttendanceScanException qrRevoked() {
        return new AttendanceScanException(
                HttpStatus.GONE,
                ApiErrorCode.QR_REVOKED,
                "더 이상 사용할 수 없는 QR입니다. 사업장에 문의해 주세요.");
    }

    /** 사업장에 좌표가 등록되어 있지 않습니다. */
    public static AttendanceScanException workplaceLocationRequired() {
        return new AttendanceScanException(
                HttpStatus.CONFLICT,
                ApiErrorCode.WORKPLACE_LOCATION_REQUIRED,
                "사업장 위치가 등록되지 않아 출퇴근을 처리할 수 없습니다.");
    }

    /** 위치 정확도 초과 또는 측정 시각 신선도 오류입니다. */
    public static AttendanceScanException locationInvalid(String message) {
        return new AttendanceScanException(
                HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.LOCATION_INVALID, message);
    }

    /** 반올림 전 거리가 승인 반경을 넘었습니다. */
    public static AttendanceScanException outsideWorkplaceRadius() {
        return new AttendanceScanException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.OUTSIDE_WORKPLACE_RADIUS,
                "사업장에서 너무 멀리 떨어져 있습니다.");
    }

    /** 활성·완료 어느 쪽에도 처리 대상 근무가 없습니다. */
    public static AttendanceScanException workCaseNotFound() {
        return new AttendanceScanException(
                HttpStatus.NOT_FOUND,
                ApiErrorCode.ATTENDANCE_WORK_CASE_NOT_FOUND,
                "지금 출퇴근할 근무를 찾을 수 없습니다.");
    }

    /** 후보가 여러 건이라 서버가 임의로 고르지 않습니다. */
    public static AttendanceScanException workCaseAmbiguous() {
        return new AttendanceScanException(
                HttpStatus.CONFLICT,
                ApiErrorCode.ATTENDANCE_WORK_CASE_AMBIGUOUS,
                "처리할 근무가 여러 건이라 자동으로 고를 수 없습니다.");
    }

    /** 이미 출근과 퇴근이 모두 기록된 근무입니다. */
    public static AttendanceScanException alreadyCompleted() {
        return new AttendanceScanException(
                HttpStatus.CONFLICT,
                ApiErrorCode.ATTENDANCE_ALREADY_COMPLETED,
                "이미 출퇴근이 모두 기록된 근무입니다.");
    }

    /** 상태·시간창이 맞지 않거나 다른 Key와 경합해 밀렸습니다. */
    public static AttendanceScanException stateConflict(String message) {
        return new AttendanceScanException(
                HttpStatus.CONFLICT, ApiErrorCode.ATTENDANCE_STATE_CONFLICT, message);
    }

    /** Deadlock·Lock Timeout이 총 3회 시도 뒤에도 계속됐습니다. */
    public static AttendanceScanException temporarilyUnavailable() {
        return new AttendanceScanException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.ATTENDANCE_TEMPORARILY_UNAVAILABLE,
                "지금은 출퇴근을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }
}
