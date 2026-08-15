package com.gighub.settlement.review;

import lombok.Getter;

/** Provider 실패를 사용자 API에 노출하지 않고 감사 코드로 전달합니다. */
@Getter
public class DisputeReviewProviderException extends RuntimeException {
    private final String failureCode;

    public DisputeReviewProviderException(String failureCode, String message) {
        super(message);
        this.failureCode = failureCode;
    }

    public DisputeReviewProviderException(String failureCode, String message, Throwable cause) {
        super(message, cause);
        this.failureCode = failureCode;
    }
}
