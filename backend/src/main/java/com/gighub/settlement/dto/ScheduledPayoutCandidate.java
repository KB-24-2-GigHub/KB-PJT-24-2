package com.gighub.settlement.dto;

import lombok.Getter;

/**
 * #172 Scheduler가 건별 짧은 Transaction에서 잠근 후보의 최소 식별 정보입니다.
 *
 * <p>MyBatis가 &lt;constructor&gt; 매핑으로 생성하므로 no-args 생성자 없이 필드를 final로
 * 고정한다.</p>
 */
@Getter
public class ScheduledPayoutCandidate {
    private final Long settlementId;
    private final Long workCaseId;

    public ScheduledPayoutCandidate(Long settlementId, Long workCaseId) {
        this.settlementId = settlementId;
        this.workCaseId = workCaseId;
    }
}
