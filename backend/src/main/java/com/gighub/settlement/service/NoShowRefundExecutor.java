package com.gighub.settlement.service;

import com.gighub.settlement.domain.SettlementCalculationReason;
import com.gighub.settlement.service.result.SettlementResult;

public interface NoShowRefundExecutor {
    SettlementResult execute(long workCaseId, long ownerUserId);

    SettlementResult execute(
            long workCaseId,
            long ownerUserId,
            SettlementCalculationReason reason);
}
