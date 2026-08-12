package com.gighub.settlement.service.command;

import com.gighub.settlement.domain.SettlementPayoutTrigger;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

/** 수동 승인과 예정 지급이 함께 사용하는 내부 지급 명령입니다. */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SettlementPayoutCommand {
    private final Long workCaseId;
    private final Long settlementId;
    private final Long actorUserId;
    private final SettlementPayoutTrigger trigger;
    private final LocalDateTime eligibilityTime;

    public static SettlementPayoutCommand ownerApproval(long workCaseId, long ownerUserId) {
        return new SettlementPayoutCommand(
                workCaseId,
                null,
                ownerUserId,
                SettlementPayoutTrigger.OWNER_APPROVAL,
                null);
    }

    /**
     * #172 Scheduler가 DB 시각으로 후보 자격을 확인한 뒤 사용할 경계입니다.
     *
     * <p>이 메서드는 Scheduler나 후보 조회를 구현하지 않습니다. 전달받은 DB 시각은 순수 정책이
     * {@code dueAt <= eligibilityTime}을 다시 확인하는 데만 사용합니다.</p>
     */
    public static SettlementPayoutCommand scheduled(
            long settlementId, long workCaseId, LocalDateTime eligibilityTime) {
        return new SettlementPayoutCommand(
                workCaseId,
                settlementId,
                null,
                SettlementPayoutTrigger.SCHEDULER,
                eligibilityTime);
    }
}
