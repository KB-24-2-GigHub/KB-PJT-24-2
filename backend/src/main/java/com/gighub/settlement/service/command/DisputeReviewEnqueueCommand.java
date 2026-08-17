package com.gighub.settlement.service.command;

import com.gighub.settlement.domain.SettlementStatus;
import com.gighub.work.contract.WorkCaseEscrowSnapshot;
import lombok.Builder;
import lombok.Getter;

/** 분쟁 생성과 같은 Transaction에서 비식별 검토 Snapshot을 예약합니다. */
@Getter
@Builder
public class DisputeReviewEnqueueCommand {
    private final Long disputeId;
    private final String title;
    private final String content;
    private final WorkCaseEscrowSnapshot workCase;
    private final SettlementStatus settlementStatus;
}
