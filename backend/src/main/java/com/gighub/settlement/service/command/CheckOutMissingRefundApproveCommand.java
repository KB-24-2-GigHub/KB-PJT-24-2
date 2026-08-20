package com.gighub.settlement.service.command;

import com.gighub.member.domain.UserRole;
import lombok.Builder;
import lombok.Value;

/** OWNER의 CHECK_OUT_MISSING 예치금 환불 승인 의도를 전달합니다. */
@Value
@Builder
public class CheckOutMissingRefundApproveCommand {
    Long workCaseId;
    Long approverUserId;
    UserRole approverRole;
    String idempotencyKey;
}
