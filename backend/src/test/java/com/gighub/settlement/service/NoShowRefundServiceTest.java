package com.gighub.settlement.service;

import com.gighub.common.exception.RoleMismatchException;
import com.gighub.idempotency.IdempotencyClaimResult;
import com.gighub.idempotency.IdempotencyClaimService;
import com.gighub.member.domain.UserRole;
import com.gighub.settlement.service.command.NoShowRefundApproveCommand;
import com.gighub.settlement.service.impl.SettlementServiceImpl;
import com.gighub.settlement.service.result.SettlementResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NoShowRefundServiceTest {

    private static final long OWNER_ID = 3L;
    private static final long WORK_CASE_ID = 1L;
    private static final long CLAIM_ID = 77L;
    private static final String KEY = "NO-SHOW-REFUND-KEY-001";
    private static final String OPERATION = "SETTLEMENT_NO_SHOW_REFUND_APPROVE";

    @Mock
    private IdempotencyClaimService claimService;

    @Mock
    private SettlementApprovalTransaction approvalTransaction;

    @Mock
    private NoShowRefundApprovalTransaction refundApprovalTransaction;

    @Mock
    private SettlementReplayCodec replayCodec;

    private SettlementService settlementService;

    @BeforeEach
    void setUp() {
        settlementService = new SettlementServiceImpl(
                claimService,
                approvalTransaction,
                refundApprovalTransaction,
                replayCodec);
    }

    @Test
    void claimsTheDedicatedOperationWithTheApprovedFingerprint() throws Exception {
        NoShowRefundApproveCommand command = command(UserRole.OWNER);
        SettlementResult expected = refunded(false);
        when(claimService.claim(eq(OWNER_ID), eq(OPERATION), eq(KEY), any()))
                .thenReturn(IdempotencyClaimResult.started(CLAIM_ID));
        when(refundApprovalTransaction.execute(command, CLAIM_ID)).thenReturn(expected);

        assertSame(expected, settlementService.approveNoShowRefund(command));

        ArgumentCaptor<byte[]> fingerprint = ArgumentCaptor.forClass(byte[].class);
        verify(claimService).claim(
                eq(OWNER_ID), eq(OPERATION), eq(KEY), fingerprint.capture());
        assertArrayEquals(
                MessageDigest.getInstance("SHA-256").digest(
                        (OPERATION + "\n" + WORK_CASE_ID)
                                .getBytes(StandardCharsets.UTF_8)),
                fingerprint.getValue());
        verify(refundApprovalTransaction).execute(command, CLAIM_ID);
    }

    @Test
    void completedClaimReplaysWithoutExecutingAnotherRefund() {
        NoShowRefundApproveCommand command = command(UserRole.OWNER);
        SettlementResult expected = refunded(true);
        when(claimService.claim(eq(OWNER_ID), eq(OPERATION), eq(KEY), any()))
                .thenReturn(IdempotencyClaimResult.replay(200, "{\"data\":{}}"));
        when(replayCodec.readResponseBody("{\"data\":{}}"))
                .thenReturn(expected);

        assertSame(expected, settlementService.approveNoShowRefund(command));

        verify(refundApprovalTransaction, never()).execute(any(), anyLong());
    }

    @Test
    void roleValidationHappensBeforeAClaimIsStored() {
        assertThrows(
                RoleMismatchException.class,
                () -> settlementService.approveNoShowRefund(command(UserRole.WORKER)));

        verify(claimService, never()).claim(anyLong(), any(), any(), any());
    }

    private NoShowRefundApproveCommand command(UserRole role) {
        return NoShowRefundApproveCommand.builder()
                .workCaseId(WORK_CASE_ID)
                .approverUserId(OWNER_ID)
                .approverRole(role)
                .idempotencyKey(KEY)
                .build();
    }

    private SettlementResult refunded(boolean replayed) {
        return SettlementResult.builder()
                .settlementId(12L)
                .status("REFUNDED")
                .settlementAmount(300_000L)
                .originalEscrowAmount(300_000L)
                .workerPaidAmount(0L)
                .ownerRefundAmount(300_000L)
                .completedAt(LocalDateTime.of(2026, 8, 13, 14, 0))
                .replayed(replayed)
                .build();
    }
}
