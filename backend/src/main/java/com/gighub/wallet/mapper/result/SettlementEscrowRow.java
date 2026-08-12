package com.gighub.wallet.mapper.result;

import com.gighub.wallet.domain.EscrowStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Wallet Mapper가 잠근 에스크로 행의 내부 Projection입니다. */
@Getter
@AllArgsConstructor
public class SettlementEscrowRow {
    private final Long escrowId;
    private final Long workCaseId;
    private final Long amount;
    private final EscrowStatus status;
}
