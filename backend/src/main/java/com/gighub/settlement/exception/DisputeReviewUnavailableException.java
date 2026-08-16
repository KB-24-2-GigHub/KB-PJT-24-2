package com.gighub.settlement.exception;

import com.gighub.common.api.ApiErrorCode;
import com.gighub.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** 외부 분쟁 검토 DEMO가 비활성화되어 안전하게 접수를 막는 409 계약입니다. */
public class DisputeReviewUnavailableException extends ApiException {

    public DisputeReviewUnavailableException() {
        super(
                HttpStatus.CONFLICT,
                ApiErrorCode.DISPUTE_REVIEW_UNAVAILABLE,
                "분쟁 검토 DEMO가 비활성화되어 있습니다."
        );
    }
}
