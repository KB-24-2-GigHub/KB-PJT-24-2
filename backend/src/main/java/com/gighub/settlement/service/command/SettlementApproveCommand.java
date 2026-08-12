package com.gighub.settlement.service.command;

import com.gighub.member.domain.UserRole;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SettlementApproveCommand {
    Long workCaseId;
    Long approverUserId;
    UserRole approverRole;
    String idempotencyKey;
}
