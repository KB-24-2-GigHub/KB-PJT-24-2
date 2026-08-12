package com.gighub.settlement.exception;

import com.gighub.common.api.ApiErrorCode;
import com.gighub.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Work·Settlement·Escrow가 수동 지급 가능한 상태가 아닌 경우입니다. */
public class SettlementNotReadyException extends ApiException {

    public SettlementNotReadyException() {
        super(
                HttpStatus.CONFLICT,
                ApiErrorCode.SETTLEMENT_NOT_READY,
                "아직 승인할 수 없는 정산입니다.");
    }
}
