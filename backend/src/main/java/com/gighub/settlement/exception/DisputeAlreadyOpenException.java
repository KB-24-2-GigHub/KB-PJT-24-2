package com.gighub.settlement.exception;

import com.gighub.common.api.ApiErrorCode;
import com.gighub.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** 같은 근무에 열린 분쟁이 이미 있는 경우의 공개 409 계약입니다. */
public class DisputeAlreadyOpenException extends ApiException {

    public DisputeAlreadyOpenException() {
        super(
                HttpStatus.CONFLICT,
                ApiErrorCode.DISPUTE_ALREADY_OPEN,
                "이미 처리 중인 분쟁이 있습니다."
        );
    }
}
