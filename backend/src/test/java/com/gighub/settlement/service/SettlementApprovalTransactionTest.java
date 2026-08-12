package com.gighub.settlement.service;

import com.gighub.common.exception.ResourceNotFoundException;
import com.gighub.idempotency.IdempotencyClaimService;
import com.gighub.member.domain.UserRole;
import com.gighub.settlement.exception.SettlementAlreadyProcessedException;
import com.gighub.settlement.exception.SettlementNotReadyException;
import com.gighub.settlement.exception.SettlementOnHoldException;
import com.gighub.settlement.service.command.SettlementApproveCommand;
import com.gighub.settlement.service.command.SettlementPayoutCommand;
import com.gighub.settlement.service.policy.SettlementPayoutDecision;
import com.gighub.settlement.service.policy.SettlementPayoutRejectedException;
import com.gighub.settlement.service.result.SettlementResult;
import com.gighub.wallet.exception.EscrowIntegrityException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.stream.Stream;

@ExtendWith(MockitoExtension.class)
class SettlementApprovalTransactionTest {

    @Mock
    private SettlementPayoutExecutor payoutExecutor;

    @Mock
    private IdempotencyClaimService claimService;

    @Mock
    private SettlementReplayCodec replayCodec;

    @InjectMocks
    private SettlementApprovalTransaction transaction;

    @Test
    void completesTheClaimOnlyAfterTheSharedPayoutExecutorSucceeds() {
        SettlementApproveCommand command = command();
        SettlementResult result = SettlementResult.builder()
                .settlementId(21L)
                .status("COMPLETED")
                .settlementAmount(300_000L)
                .originalEscrowAmount(300_000L)
                .workerPaidAmount(300_000L)
                .ownerRefundAmount(0L)
                .build();
        when(payoutExecutor.execute(org.mockito.ArgumentMatchers.any()))
                .thenReturn(result);
        when(replayCodec.writeResponseBody(result)).thenReturn("{\"data\":{}}");

        assertSame(result, transaction.execute(command, 77L));

        ArgumentCaptor<SettlementPayoutCommand> payoutCommand =
                ArgumentCaptor.forClass(SettlementPayoutCommand.class);
        verify(payoutExecutor).execute(payoutCommand.capture());
        assertEquals(11L, payoutCommand.getValue().getWorkCaseId());
        assertEquals(3L, payoutCommand.getValue().getActorUserId());

        InOrder order = inOrder(payoutExecutor, replayCodec, claimService);
        order.verify(payoutExecutor).execute(org.mockito.ArgumentMatchers.any());
        order.verify(replayCodec).writeResponseBody(result);
        order.verify(claimService).complete(77L, 200, "{\"data\":{}}");
    }

    @ParameterizedTest
    @MethodSource("ownerRejectionMappings")
    void mapsNeutralPayoutDecisionsOnlyAtTheOwnerApprovalBoundary(
            SettlementPayoutDecision decision,
            Class<? extends RuntimeException> expectedException) {
        when(payoutExecutor.execute(any()))
                .thenThrow(new SettlementPayoutRejectedException(decision));

        assertThrows(expectedException, () -> transaction.execute(command(), 77L));

        verify(replayCodec, never()).writeResponseBody(any());
        verify(claimService, never()).complete(anyLong(), anyInt(), anyString());
    }

    private static Stream<Arguments> ownerRejectionMappings() {
        return Stream.of(
                Arguments.of(
                        SettlementPayoutDecision.RESOURCE_NOT_FOUND,
                        ResourceNotFoundException.class),
                Arguments.of(
                        SettlementPayoutDecision.NOT_READY,
                        SettlementNotReadyException.class),
                Arguments.of(
                        SettlementPayoutDecision.ON_HOLD,
                        SettlementOnHoldException.class),
                Arguments.of(
                        SettlementPayoutDecision.ALREADY_PROCESSED,
                        SettlementAlreadyProcessedException.class),
                Arguments.of(
                        SettlementPayoutDecision.INTEGRITY_VIOLATION,
                        EscrowIntegrityException.class));
    }

    private SettlementApproveCommand command() {
        return SettlementApproveCommand.builder()
                .workCaseId(11L)
                .approverUserId(3L)
                .approverRole(UserRole.OWNER)
                .idempotencyKey("EXTERNAL-KEY")
                .build();
    }
}
