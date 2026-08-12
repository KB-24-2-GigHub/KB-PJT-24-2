package com.gighub.settlement.exception;

import com.gighub.common.api.ApiErrorCode;
import com.gighub.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** 열린 분쟁 또는 ON_HOLD 상태가 지급을 막는 경우입니다. */
public class SettlementOnHoldException extends ApiException {

    public SettlementOnHoldException() {
        super(
                HttpStatus.CONFLICT,
                ApiErrorCode.SETTLEMENT_ON_HOLD,
                "분쟁 처리 중인 근무 건은 정산할 수 없습니다.");
    }
}
