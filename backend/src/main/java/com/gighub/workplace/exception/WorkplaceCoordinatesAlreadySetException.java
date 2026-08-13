package com.gighub.workplace.exception;

import com.gighub.common.api.ApiErrorCode;
import com.gighub.common.exception.ApiException;

import org.springframework.http.HttpStatus;

/**
 * 이미 확정된 사업장 좌표를 다른 값으로 재요청한 것을 승인된 409 계약으로 반환합니다.
 *
 * <p>같은 정규화 좌표의 재전송은 응답 유실 재시도이므로 성공(204)으로 처리하고, 이
 * 예외는 다른 값일 때만 던집니다(API_SPEC "사업장 출퇴근 위치 확정").</p>
 */
public class WorkplaceCoordinatesAlreadySetException extends ApiException {

    public WorkplaceCoordinatesAlreadySetException(String message) {
        super(HttpStatus.CONFLICT, ApiErrorCode.WORKPLACE_COORDINATES_ALREADY_SET, message);
    }
}
