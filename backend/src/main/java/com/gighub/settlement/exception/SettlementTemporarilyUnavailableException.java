package com.gighub.settlement.exception;

import com.gighub.common.api.ApiErrorCode;
import com.gighub.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** 제한된 내부 재시도 뒤에도 DB 잠금 경합이 계속된 경우입니다. */
public class SettlementTemporarilyUnavailableException extends ApiException {

    public SettlementTemporarilyUnavailableException() {
        super(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.SETTLEMENT_TEMPORARILY_UNAVAILABLE,
                "정산 요청을 잠시 처리할 수 없습니다. 같은 멱등 키로 다시 시도해 주세요.");
    }
}
