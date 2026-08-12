package com.gighub.settlement.service;

import com.gighub.common.exception.RoleMismatchException;
import com.gighub.idempotency.IdempotencyClaimResult;
import com.gighub.idempotency.IdempotencyClaimService;
import com.gighub.member.domain.UserRole;
import com.gighub.settlement.exception.SettlementNotReadyException;
import com.gighub.settlement.exception.SettlementTemporarilyUnavailableException;
import com.gighub.settlement.service.command.SettlementApproveCommand;
import com.gighub.settlement.service.impl.SettlementServiceImpl;
import com.gighub.settlement.service.result.SettlementResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.PessimisticLockingFailureException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 외부 Key Claim과 지급 Transaction의 분리된 생명주기를 고정합니다. */
@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {

    private static final long EMPLOYER_ID = 3L;
    private static final long WORK_CASE_ID = 1L;
    private static final long CLAIM_ID = 77L;
    private static final long WAGE = 300_000L;
    private static final String KEY = "SETTLEMENT-KEY-001";

    @Mock
    private IdempotencyClaimService claimService;

    @Mock
    private SettlementPayoutExecutor payoutExecutor;

    @Mock
    private SettlementReplayCodec replayCodec;

    @InjectMocks
    private SettlementServiceImpl settlementService;

    @Test
    void firstRequestClaimsTheExternalKeyAndExecutesOnePayoutTransaction() {
        SettlementApproveCommand command = command(WORK_CASE_ID, KEY, UserRole.OWNER);
        SettlementResult expected = completed(false);
        when(claimService.claim(eq(EMPLOYER_ID), eq("SETTLEMENT_APPROVE"), eq(KEY), any()))
                .thenReturn(IdempotencyClaimResult.started(CLAIM_ID));
        when(payoutExecutor.execute(command, CLAIM_ID)).thenReturn(expected);

        assertSame(expected, settlementService.approve(command));

        verify(payoutExecutor).execute(command, CLAIM_ID);
        verify(claimService, never()).abandon(anyLong());
    }

    @Test
    void completedClaimReplaysTheStoredResponseWithoutReadingCurrentSettlementState() {
        SettlementApproveCommand command = command(WORK_CASE_ID, KEY, UserRole.OWNER);
        SettlementResult expected = completed(true);
        when(claimService.claim(eq(EMPLOYER_ID), eq("SETTLEMENT_APPROVE"), eq(KEY), any()))
                .thenReturn(IdempotencyClaimResult.replay(200, "{\"data\":{}}"));
        when(replayCodec.readResponseBody("{\"data\":{}}"))
                .thenReturn(expected);

        assertSame(expected, settlementService.approve(command));

        verify(payoutExecutor, never()).execute(any(), anyLong());
    }

    @Test
    void failedPayoutAbandonsTheClaimAfterTheTransactionReturns() {
        SettlementApproveCommand command = command(WORK_CASE_ID, KEY, UserRole.OWNER);
        SettlementNotReadyException failure = new SettlementNotReadyException();
        when(claimService.claim(eq(EMPLOYER_ID), eq("SETTLEMENT_APPROVE"), eq(KEY), any()))
                .thenReturn(IdempotencyClaimResult.started(CLAIM_ID));
        when(payoutExecutor.execute(command, CLAIM_ID)).thenThrow(failure);

        assertSame(failure, assertThrows(
                SettlementNotReadyException.class,
                () -> settlementService.approve(command)));
        verify(claimService).abandon(CLAIM_ID);
    }

    @Test
    void lockFailuresRetryTheWholeTransactionThenReleaseTheExternalKey() {
        SettlementApproveCommand command = command(WORK_CASE_ID, KEY, UserRole.OWNER);
        when(claimService.claim(eq(EMPLOYER_ID), eq("SETTLEMENT_APPROVE"), eq(KEY), any()))
                .thenReturn(IdempotencyClaimResult.started(CLAIM_ID));
        when(payoutExecutor.execute(command, CLAIM_ID))
                .thenThrow(new PessimisticLockingFailureException("lock"));

        assertThrows(
                SettlementTemporarilyUnavailableException.class,
                () -> settlementService.approve(command));

        verify(payoutExecutor, times(3)).execute(command, CLAIM_ID);
        verify(claimService).abandon(CLAIM_ID);
    }

    @Test
    void roleAndKeyValidationHappenBeforeAClaimIsStored() {
        assertThrows(
                RoleMismatchException.class,
                () -> settlementService.approve(
                        command(WORK_CASE_ID, KEY, UserRole.WORKER)));
        assertThrows(
                com.gighub.common.exception.ValidationException.class,
                () -> settlementService.approve(
                        command(WORK_CASE_ID, "contains space", UserRole.OWNER)));

        verify(claimService, never()).claim(anyLong(), any(), any(), any());
    }

    @Test
    void fingerprintUsesWorkCaseIdentityButNeverTheExternalKey() {
        SettlementApproveCommand first = command(WORK_CASE_ID, KEY, UserRole.OWNER);
        SettlementApproveCommand second = command(WORK_CASE_ID, "ANOTHER-KEY", UserRole.OWNER);
        when(claimService.claim(anyLong(), any(), any(), any()))
                .thenReturn(IdempotencyClaimResult.started(CLAIM_ID));
        when(payoutExecutor.execute(any(), eq(CLAIM_ID))).thenReturn(completed(false));

        settlementService.approve(first);
        settlementService.approve(second);

        ArgumentCaptor<byte[]> fingerprints = ArgumentCaptor.forClass(byte[].class);
        verify(claimService, times(2)).claim(
                eq(EMPLOYER_ID), eq("SETTLEMENT_APPROVE"), any(), fingerprints.capture());
        assertArrayEquals(
                fingerprints.getAllValues().get(0),
                fingerprints.getAllValues().get(1));
        assertEquals(32, fingerprints.getValue().length);
        assertTrue(fingerprints.getValue().length < KEY.length() * 4);
    }

    private SettlementApproveCommand command(
            long workCaseId, String key, UserRole role) {
        return SettlementApproveCommand.builder()
                .workCaseId(workCaseId)
                .approverUserId(EMPLOYER_ID)
                .approverRole(role)
                .idempotencyKey(key)
                .build();
    }

    private SettlementResult completed(boolean replayed) {
        return SettlementResult.builder()
                .settlementId(12L)
                .status("COMPLETED")
                .settlementAmount(WAGE)
                .originalEscrowAmount(WAGE)
                .workerPaidAmount(WAGE)
                .ownerRefundAmount(0L)
                .completedAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .replayed(replayed)
                .build();
    }
}
