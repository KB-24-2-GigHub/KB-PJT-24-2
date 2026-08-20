package com.gighub.settlement.service.command;

import com.gighub.settlement.domain.SettlementCalculationReason;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/** 근태 Transaction이 최초 정산 Snapshot을 확정할 때 전달하는 원본 사실입니다. */
@Value
@Builder
public class SettlementCalculationCommand {
    Long workCaseId;
    Long agreedWage;
    LocalDateTime startsAt;
    LocalDateTime endsAt;
    Integer breakMinutes;
    Boolean breakPaid;
    LocalDateTime checkedInAt;
    LocalDateTime checkedOutAt;
    SettlementCalculationReason reason;
}
