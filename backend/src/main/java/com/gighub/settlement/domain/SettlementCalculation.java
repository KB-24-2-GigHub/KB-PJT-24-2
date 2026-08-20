package com.gighub.settlement.domain;

import lombok.Builder;
import lombok.Value;

/** 자금 실행 전에 한 번 확정해 Settlement에 저장하는 금액 계산 결과입니다. */
@Value
@Builder
public class SettlementCalculation {
    long originalAmount;
    long workerPaidAmount;
    long ownerRefundAmount;
    long deductionBaseMinutes;
    long lateMinutes;
    long earlyLeaveMinutes;
    SettlementCalculationReason reason;
    String version;
}
