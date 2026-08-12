package com.gighub.settlement.service;

import com.gighub.settlement.service.command.SettlementPayoutCommand;
import com.gighub.settlement.service.result.SettlementResult;

/** 수동 승인과 #172 Scheduler의 건별 Transaction이 공유하는 원자 지급 경계입니다. */
public interface SettlementPayoutExecutor {

    /**
     * 호출자가 연 Transaction 안에서 상태·Escrow·Wallet·원장을 한 번만 확정합니다.
     *
     * <p>구현은 {@code MANDATORY}로 참여하므로 Scheduler도 후보 조회 Transaction과 분리한
     * 짧은 건별 Transaction을 먼저 열어야 합니다.</p>
     */
    SettlementResult execute(SettlementPayoutCommand command);
}
