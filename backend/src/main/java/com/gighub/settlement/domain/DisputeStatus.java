package com.gighub.settlement.domain;

/** 분쟁 행에 저장되는 상태입니다. 열린 상태만 정산과 환불을 보류합니다. */
public enum DisputeStatus {
    OPEN,
    UNDER_REVIEW,
    RESOLVED,
    REJECTED,
    CANCELED
}
