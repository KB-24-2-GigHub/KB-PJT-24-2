package com.gighub.document.exception;

import com.gighub.common.api.ApiErrorCode;
import com.gighub.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** 근로계약서를 사용자 요청으로 삭제하려는 시도를 보존 정책으로 거부합니다(DOC-006). */
public class ContractRetentionRequiredException extends ApiException {

    public ContractRetentionRequiredException(String message) {
        super(HttpStatus.CONFLICT, ApiErrorCode.CONTRACT_RETENTION_REQUIRED, message);
    }
}
