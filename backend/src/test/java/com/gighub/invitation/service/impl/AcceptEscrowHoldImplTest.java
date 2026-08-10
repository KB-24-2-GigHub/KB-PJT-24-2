package com.gighub.invitation.service.impl;

import com.gighub.common.exception.ConflictException;
import com.gighub.wallet.dto.WalletBalanceSnapshot;
import com.gighub.wallet.mapper.WalletMapper;
import com.gighub.wallet.mapper.param.WalletTransactionParam;
import com.gighub.wallet.service.impl.AcceptEscrowHoldImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 수락 예치가 잔액·원장·에스크로를 승인된 값으로 남기는지 확인합니다.
 */
class AcceptEscrowHoldImplTest {

    private static final long EMPLOYER_ID = 7L;
    private static final long WORK_CASE_ID = 101L;
    private static final long WAGE = 120_000L;
    private static final long CLAIM_ID = 55L;
    private static final long ESCROW_ID = 900L;
    private static final LocalDateTime ACCEPTED_AT = LocalDateTime.of(2026, 8, 20, 10, 0);

    private final WalletMapper walletMapper = mock(WalletMapper.class);
    private final AcceptEscrowHoldImpl escrowHold = new AcceptEscrowHoldImpl(walletMapper);

    @Test
    void movesAvailableToLockedAndRecordsLedgerSnapshot() {
        givenWallet(500_000L, 30_000L);

        long escrowId = escrowHold.hold(EMPLOYER_ID, WORK_CASE_ID, WAGE, CLAIM_ID, ACCEPTED_AT);

        assertEquals(ESCROW_ID, escrowId);
        verify(walletMapper).lockEmployerFunds(EMPLOYER_ID, WAGE);
        // 에스크로는 Aggregate가 공유하는 시각으로 HELD가 됩니다.
        verify(walletMapper).insertHeldEscrowAt(WORK_CASE_ID, WAGE, ACCEPTED_AT);

        ArgumentCaptor<WalletTransactionParam> ledger =
                ArgumentCaptor.forClass(WalletTransactionParam.class);
        verify(walletMapper).insertWalletTransaction(ledger.capture());
        WalletTransactionParam recorded = ledger.getValue();

        assertEquals("ESCROW_HOLD", recorded.getTransactionType());
        assertEquals(WAGE, recorded.getAmount());
        assertEquals(500_000L, recorded.getAvailableBefore());
        assertEquals(380_000L, recorded.getAvailableAfter());
        assertEquals(30_000L, recorded.getLockedBefore());
        assertEquals(150_000L, recorded.getLockedAfter());
        assertEquals("ESCROW", recorded.getReferenceType());
        assertEquals(ESCROW_ID, recorded.getReferenceId());
    }

    @Test
    void ledgerKeyIsDerivedFromTheClaimNotTheRequestHeader() {
        givenWallet(500_000L, 0L);

        escrowHold.hold(EMPLOYER_ID, WORK_CASE_ID, WAGE, CLAIM_ID, ACCEPTED_AT);

        ArgumentCaptor<WalletTransactionParam> ledger =
                ArgumentCaptor.forClass(WalletTransactionParam.class);
        verify(walletMapper).insertWalletTransaction(ledger.capture());

        assertEquals(expectedLedgerKey(CLAIM_ID), ledger.getValue().getIdempotencyKey());
    }

    @Test
    void insufficientBalanceIsRejectedWithoutRevealingTheAmount() {
        givenWallet(WAGE - 1L, 0L);

        ConflictException failure = assertThrows(
                ConflictException.class,
                () -> escrowHold.hold(EMPLOYER_ID, WORK_CASE_ID, WAGE, CLAIM_ID, ACCEPTED_AT)
        );

        assertEquals(
                "사장님의 예치 가능 잔액이 부족하여 근무를 확정할 수 없습니다.",
                failure.getMessage()
        );
        // 잔액 수치는 메시지에 담지 않습니다.
        assertEquals(false, failure.getMessage().contains(String.valueOf(WAGE - 1L)));
        verify(walletMapper, never()).lockEmployerFunds(any(), any());
        verify(walletMapper, never()).insertWalletTransaction(any());
    }

    @Test
    void balanceExactlyEqualToTheWageIsEnough() {
        givenWallet(WAGE, 0L);

        escrowHold.hold(EMPLOYER_ID, WORK_CASE_ID, WAGE, CLAIM_ID, ACCEPTED_AT);

        verify(walletMapper).lockEmployerFunds(EMPLOYER_ID, WAGE);
    }

    @Test
    void unexpectedBalanceUpdateResultStopsTheAggregate() {
        givenWallet(500_000L, 0L);
        when(walletMapper.lockEmployerFunds(EMPLOYER_ID, WAGE)).thenReturn(0);

        assertThrows(
                IllegalStateException.class,
                () -> escrowHold.hold(EMPLOYER_ID, WORK_CASE_ID, WAGE, CLAIM_ID, ACCEPTED_AT)
        );
        verify(walletMapper, never()).insertWalletTransaction(any());
    }

    @Test
    void missingOwnerWalletIsAnIntegrityFailure() {
        when(walletMapper.getWalletSnapshotForUpdate(EMPLOYER_ID)).thenReturn(null);

        assertThrows(
                IllegalStateException.class,
                () -> escrowHold.hold(EMPLOYER_ID, WORK_CASE_ID, WAGE, CLAIM_ID, ACCEPTED_AT)
        );
    }

    private void givenWallet(long available, long locked) {
        when(walletMapper.getWalletSnapshotForUpdate(EMPLOYER_ID)).thenReturn(
                WalletBalanceSnapshot.builder()
                        .walletId(11L)
                        .userId(EMPLOYER_ID)
                        .availableBalance(available)
                        .lockedBalance(locked)
                        .build());
        when(walletMapper.lockEmployerFunds(EMPLOYER_ID, WAGE)).thenReturn(1);
        when(walletMapper.insertHeldEscrowAt(eq(WORK_CASE_ID), eq(WAGE), any())).thenReturn(1);
        when(walletMapper.getEscrowIdByWorkCaseId(WORK_CASE_ID)).thenReturn(ESCROW_ID);
        when(walletMapper.insertWalletTransaction(any())).thenReturn(1);
    }

    private static String expectedLedgerKey(long claimId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("INVITATION_ACCEPT\n" + claimId).getBytes(StandardCharsets.UTF_8));
            return "EHLD:" + HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
