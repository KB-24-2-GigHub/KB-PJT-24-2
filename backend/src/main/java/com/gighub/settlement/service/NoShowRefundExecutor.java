package com.gighub.settlement.service;

import com.gighub.settlement.service.result.SettlementResult;

public interface NoShowRefundExecutor {
    SettlementResult execute(long workCaseId, long ownerUserId);
}
