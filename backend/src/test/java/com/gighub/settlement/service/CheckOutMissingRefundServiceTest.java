package com.gighub.settlement.service;

import com.gighub.common.exception.RoleMismatchException;
import com.gighub.idempotency.IdempotencyClaimResult;
import com.gighub.idempotency.IdempotencyClaimService;
import com.gighub.member.domain.UserRole;
import com.gighub.settlement.service.command.CheckOutMissingRefundApproveCommand;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckOutMissingRefundServiceTest {

    private static final long OWNER_ID = 3L;
    private static final long WORK_CASE_ID = 1L;
    private static final long CLAIM_ID = 77L;
    private static final String KEY = "CHECK-OUT-MISSING-REFUND-001";
    private static final String OPERATION =
            "SETTLEMENT_CHECK_OUT_MISSING_REFUND_APPROVE";

    @Mock private IdempotencyClaimService claimService;
    @Mock private SettlementApprovalTransaction approvalTransaction;
    @Mock private NoShowRefundApprovalTransaction noShowRefundApprovalTransaction;
    @Mock private CheckOutMissingRefundApprovalTransaction missingRefundTransaction;
    @Mock private SettlementReplayCodec replayCodec;

    private SettlementService service;

    @BeforeEach
    void setUp() {
        service = new SettlementServiceImpl(
                claimService,
                approvalTransaction,
                noShowRefundApprovalTransaction,
                missingRefundTransaction,
                replayCodec);
    }

    @Test
    void claimsTheDedicatedOperationAndExecutesItsOwnTransaction() throws Exception {
        CheckOutMissingRefundApproveCommand command = command(UserRole.OWNER);
        SettlementResult expected = SettlementResult.builder().settlementId(12L).build();
        when(claimService.claim(eq(OWNER_ID), eq(OPERATION), eq(KEY), any()))
                .thenReturn(IdempotencyClaimResult.started(CLAIM_ID));
        when(missingRefundTransaction.execute(command, CLAIM_ID)).thenReturn(expected);

        assertSame(expected, service.approveCheckOutMissingRefund(command));

        ArgumentCaptor<byte[]> fingerprint = ArgumentCaptor.forClass(byte[].class);
        verify(claimService).claim(
                eq(OWNER_ID), eq(OPERATION), eq(KEY), fingerprint.capture());
        assertArrayEquals(
                MessageDigest.getInstance("SHA-256").digest(
                        (OPERATION + "\n" + WORK_CASE_ID)
                                .getBytes(StandardCharsets.US_ASCII)),
                fingerprint.getValue());
        verify(missingRefundTransaction).execute(command, CLAIM_ID);
        verify(noShowRefundApprovalTransaction, never()).execute(any(), eq(CLAIM_ID));
    }

    @Test
    void rejectsNonOwnerBeforeCreatingAClaim() {
        assertThrows(
                RoleMismatchException.class,
                () -> service.approveCheckOutMissingRefund(command(UserRole.WORKER)));

        verify(claimService, never()).claim(
                anyLong(), anyString(), anyString(), any(byte[].class));
    }

    private CheckOutMissingRefundApproveCommand command(UserRole role) {
        return CheckOutMissingRefundApproveCommand.builder()
                .workCaseId(WORK_CASE_ID)
                .approverUserId(OWNER_ID)
                .approverRole(role)
                .idempotencyKey(KEY)
                .build();
    }
}
