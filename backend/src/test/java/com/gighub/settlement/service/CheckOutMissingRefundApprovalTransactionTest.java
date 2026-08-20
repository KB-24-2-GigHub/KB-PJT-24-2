package com.gighub.settlement.service;

import com.gighub.idempotency.IdempotencyClaimService;
import com.gighub.member.domain.UserRole;
import com.gighub.settlement.domain.SettlementCalculationReason;
import com.gighub.settlement.service.command.CheckOutMissingRefundApproveCommand;
import com.gighub.settlement.service.result.SettlementResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckOutMissingRefundApprovalTransactionTest {

    @Mock private NoShowRefundExecutor refundExecutor;
    @Mock private IdempotencyClaimService claimService;
    @Mock private SettlementReplayCodec replayCodec;

    @InjectMocks
    private CheckOutMissingRefundApprovalTransaction transaction;

    @Test
    void usesTheSharedRefundExecutorWithTheMissingCheckoutReason() {
        CheckOutMissingRefundApproveCommand command =
                CheckOutMissingRefundApproveCommand.builder()
                        .workCaseId(11L)
                        .approverUserId(3L)
                        .approverRole(UserRole.OWNER)
                        .idempotencyKey("MISSING-REFUND")
                        .build();
        SettlementResult result = SettlementResult.builder().settlementId(21L).build();
        when(refundExecutor.execute(
                11L, 3L, SettlementCalculationReason.CHECK_OUT_MISSING))
                .thenReturn(result);
        when(replayCodec.writeResponseBody(result)).thenReturn("{\"data\":{}}");

        assertSame(result, transaction.execute(command, 77L));

        InOrder order = inOrder(refundExecutor, replayCodec, claimService);
        order.verify(refundExecutor).execute(
                11L, 3L, SettlementCalculationReason.CHECK_OUT_MISSING);
        order.verify(replayCodec).writeResponseBody(result);
        order.verify(claimService).complete(77L, 200, "{\"data\":{}}");
    }
}
