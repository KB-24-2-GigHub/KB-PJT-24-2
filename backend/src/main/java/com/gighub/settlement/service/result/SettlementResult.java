package com.gighub.settlement.service.result;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class SettlementResult {
    Long settlementId;
    String status;
    Long settlementAmount;
    Long originalEscrowAmount;
    Long workerPaidAmount;
    Long ownerRefundAmount;
    LocalDateTime completedAt;
    boolean replayed;
}
