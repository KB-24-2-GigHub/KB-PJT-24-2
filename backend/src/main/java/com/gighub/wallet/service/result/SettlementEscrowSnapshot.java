package com.gighub.wallet.service.result;

import com.gighub.wallet.domain.EscrowStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** 정산 실행기가 잠근 에스크로의 최소 공개 Snapshot입니다. */
@Getter
@Builder(toBuilder = true)
@AllArgsConstructor
public class SettlementEscrowSnapshot {
    private final Long escrowId;
    private final Long workCaseId;
    private final Long amount;
    private final EscrowStatus status;
}
