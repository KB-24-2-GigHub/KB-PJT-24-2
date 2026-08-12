package com.gighub.settlement.exception;

import com.gighub.common.api.ApiErrorCode;
import com.gighub.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** 다른 외부 Key 또는 실행 주체가 이미 지급을 완료한 경우입니다. */
public class SettlementAlreadyProcessedException extends ApiException {

    public SettlementAlreadyProcessedException() {
        super(
                HttpStatus.CONFLICT,
                ApiErrorCode.SETTLEMENT_ALREADY_PROCESSED,
                "이미 처리된 정산입니다.");
    }
}
