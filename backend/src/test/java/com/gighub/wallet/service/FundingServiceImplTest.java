package com.gighub.wallet.service;

import com.gighub.bank.exception.BankAccountForbiddenException;
import com.gighub.bank.exception.BankTransferIntegrityException;
import com.gighub.bank.service.BankAccountPreflightCommand;
import com.gighub.bank.service.BankTransferCommand;
import com.gighub.bank.service.BankTransferGateway;
import com.gighub.bank.service.BankTransferResult;
import com.gighub.wallet.domain.Money;
import com.gighub.wallet.dto.FundingOrder;
import com.gighub.wallet.dto.WalletBalanceSnapshot;
import com.gighub.wallet.dto.WalletTransactionSnapshot;
import com.gighub.wallet.exception.FundingIntegrityException;
import com.gighub.wallet.exception.IdempotencyKeyReusedException;
import com.gighub.wallet.exception.InvalidFundingRequestException;
import com.gighub.wallet.idempotency.WalletIdempotencyKeys;
import com.gighub.wallet.mapper.FundingMapper;
import com.gighub.wallet.mapper.WalletMapper;
import com.gighub.wallet.mapper.param.FundingOrderParam;
import com.gighub.wallet.mapper.param.WalletBalanceUpdateParam;
import com.gighub.wallet.mapper.param.WalletTransactionParam;
import com.gighub.wallet.service.command.FundingCommand;
import com.gighub.wallet.service.impl.FundingServiceImpl;
import com.gighub.wallet.service.impl.FundingTransactionExecutor;
import com.gighub.wallet.service.result.FundingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FundingServiceImplTest {

    private static final Long EMPLOYER_ID = 3L;
    private static final Long ACCOUNT_ID = 10L;
    private static final String BANK_CODE = "004";
    private static final String ACCOUNT_NO = "1234567890";
    private static final String PIN = "0000";
    private static final Long AMOUNT = 300_000L;
    private static final Long WALLET_ID = 50L;
    private static final Long ORDER_ID = 21L;
    private static final Long BANK_TRANSACTION_ID = 31L;
    private static final String KEY = "FUNDING-TEST-001";

    @Mock
    private FundingMapper fundingMapper;

    @Mock
    private WalletMapper walletMapper;

    @Mock
    private BankTransferGateway bankTransferGateway;

    @Mock
    private FundingTransactionExecutor transactionExecutor;

    @InjectMocks
    private FundingServiceImpl fundingService;

    @BeforeEach
    void executeAttemptInsideTestTransactionBoundary() {
        org.mockito.Mockito.lenient()
                .when(transactionExecutor.execute(any()))
                .thenAnswer(invocation -> {
                    Supplier<FundingResult> attempt = invocation.getArgument(0);
                    return attempt.get();
                });
        org.mockito.Mockito.lenient()
                .when(walletMapper.resolveWalletId(EMPLOYER_ID, Money.KRW))
                .thenReturn(WALLET_ID);
        org.mockito.Mockito.lenient()
                .when(walletMapper.getWalletSnapshotForUpdateByWalletId(WALLET_ID))
                .thenReturn(wallet(50L, 700_000L, 20_000L));
        org.mockito.Mockito.lenient()
                .when(bankTransferGateway.resolveAccountId(BANK_CODE, ACCOUNT_NO))
                .thenReturn(ACCOUNT_ID);
    }

    @Test
    @DisplayName("각 충전 재시도는 반드시 새로운 트랜잭션에서 실행한다")
    void fundingAttemptRequiresNewTransaction() throws NoSuchMethodException {
        Transactional annotation = FundingTransactionExecutor.class
                .getMethod("execute", Supplier.class)
                .getAnnotation(Transactional.class);

        assertEquals(Propagation.REQUIRES_NEW, annotation.propagation());
    }

    @Test
    @DisplayName("충전은 지갑을 잠근 뒤 주문을 선점하고 계좌를 처리한다")
    void fundingLocksWalletThenClaimsBeforeAccountAccess() {
        when(fundingMapper.insertFundingOrder(any())).thenAnswer(invocation -> {
            FundingOrderParam param = invocation.getArgument(0);
            param.setId(ORDER_ID);
            return 1;
        });
        when(walletMapper.getWalletSnapshotForUpdateByWalletId(WALLET_ID))
                .thenReturn(wallet(50L, 700_000L, 20_000L));
        when(bankTransferGateway.withdraw(any())).thenReturn(successfulTransfer());
        when(fundingMapper.completeFundingOrder(
                ORDER_ID, AMOUNT, BANK_TRANSACTION_ID)).thenReturn(1);
        when(walletMapper.updateWalletBalanceByWalletId(any())).thenReturn(1);
        when(walletMapper.insertWalletTransaction(any())).thenReturn(1);

        FundingResult result = fundingService.fund(command(AMOUNT, KEY));

        assertEquals(ORDER_ID, result.getFundingOrderId());
        assertEquals(BANK_TRANSACTION_ID, result.getBankTransactionId());
        assertEquals(1_000_000L, result.getAvailableBalance());
        assertEquals(20_000L, result.getLockedBalance());
        assertFalse(result.isReplayed());

        // 호출 순서를 계약으로 고정해 내부 재시도가 잠금 순서 회귀를 가리지 않도록 한다.
        InOrder order =
                inOrder(fundingMapper, bankTransferGateway, walletMapper);
        order.verify(walletMapper).resolveWalletId(EMPLOYER_ID, Money.KRW);
        order.verify(walletMapper).getWalletSnapshotForUpdateByWalletId(WALLET_ID);
        order.verify(fundingMapper).insertFundingOrder(any());
        order.verify(bankTransferGateway).preflight(any(BankAccountPreflightCommand.class));
        order.verify(bankTransferGateway).withdraw(any(BankTransferCommand.class));

        ArgumentCaptor<FundingOrderParam> orderCaptor =
                ArgumentCaptor.forClass(FundingOrderParam.class);
        verify(fundingMapper).insertFundingOrder(orderCaptor.capture());
        assertEquals(KEY, orderCaptor.getValue().getIdempotencyKey());

        ArgumentCaptor<WalletBalanceUpdateParam> balanceCaptor =
                ArgumentCaptor.forClass(WalletBalanceUpdateParam.class);
        verify(walletMapper).updateWalletBalanceByWalletId(balanceCaptor.capture());
        WalletBalanceUpdateParam balanceUpdate = balanceCaptor.getValue();
        assertEquals(WALLET_ID, balanceUpdate.getWalletId());
        assertEquals(Money.KRW, balanceUpdate.getCurrency());
        assertEquals(700_000L, balanceUpdate.getAvailableBefore());
        assertEquals(1_000_000L, balanceUpdate.getAvailableAfter());
        assertEquals(20_000L, balanceUpdate.getLockedBefore());
        assertEquals(20_000L, balanceUpdate.getLockedAfter());

        ArgumentCaptor<WalletTransactionParam> ledgerCaptor =
                ArgumentCaptor.forClass(WalletTransactionParam.class);
        verify(walletMapper).insertWalletTransaction(ledgerCaptor.capture());
        WalletTransactionParam ledger = ledgerCaptor.getValue();
        assertEquals(700_000L, ledger.getAvailableBefore());
        assertEquals(1_000_000L, ledger.getAvailableAfter());
        assertEquals(20_000L, ledger.getLockedBefore());
        assertEquals(20_000L, ledger.getLockedAfter());
        assertEquals(WalletIdempotencyKeys.funding(KEY), ledger.getIdempotencyKey());

        verify(walletMapper, never()).getAvailableBalance(anyLong());
        verify(walletMapper, never()).getLockedBalance(anyLong());
        verify(walletMapper).resolveWalletId(EMPLOYER_ID, Money.KRW);
    }

    @Test
    @DisplayName("동일한 동시 충전 요청은 UNIQUE 충돌 후 저장 원장을 재응답한다")
    void duplicateSameRequestReplaysStoredLedger() {
        when(fundingMapper.insertFundingOrder(any()))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(fundingMapper.findByIdempotencyKeyForShare(KEY))
                .thenReturn(completedOrder(AMOUNT));
        when(walletMapper.findFundingTransactionSnapshot(
                ORDER_ID, WALLET_ID, WalletIdempotencyKeys.funding(KEY)))
                .thenReturn(fundingSnapshot(1_000_000L, 20_000L, AMOUNT));

        FundingResult result = fundingService.fund(command(AMOUNT, KEY));

        assertTrue(result.isReplayed());
        assertEquals(1_000_000L, result.getAvailableBalance());
        assertEquals(20_000L, result.getLockedBalance());
        assertEquals(BANK_TRANSACTION_ID, result.getBankTransactionId());
        verify(bankTransferGateway, never()).preflight(any());
        verify(bankTransferGateway, never()).withdraw(any());
        verify(walletMapper, never()).updateWalletBalanceByWalletId(any());
        verify(walletMapper).getWalletSnapshotForUpdateByWalletId(WALLET_ID);
    }

    @Test
    @DisplayName("충전 Replay는 현재 지갑의 산술 가능 범위와 무관하게 저장 원장을 응답한다")
    void duplicateReplayIgnoresCurrentWalletOverflow() {
        when(walletMapper.getWalletSnapshotForUpdateByWalletId(WALLET_ID))
                .thenReturn(wallet(WALLET_ID, Long.MAX_VALUE, 20_000L));
        when(fundingMapper.insertFundingOrder(any()))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(fundingMapper.findByIdempotencyKeyForShare(KEY))
                .thenReturn(completedOrder(AMOUNT));
        when(walletMapper.findFundingTransactionSnapshot(
                ORDER_ID, WALLET_ID, WalletIdempotencyKeys.funding(KEY)))
                .thenReturn(fundingSnapshot(1_000_000L, 20_000L, AMOUNT));

        FundingResult result = fundingService.fund(command(AMOUNT, KEY));

        assertTrue(result.isReplayed());
        verify(walletMapper, never()).updateWalletBalanceByWalletId(any());
        verify(bankTransferGateway, never()).withdraw(any());
    }

    @Test
    @DisplayName("같은 멱등 키의 요청 본문이 다르면 충전하지 않는다")
    void duplicateDifferentRequestIsRejected() {
        when(fundingMapper.insertFundingOrder(any()))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(fundingMapper.findByIdempotencyKeyForShare(KEY))
                .thenReturn(completedOrder(AMOUNT + 1));

        assertThrows(
                IdempotencyKeyReusedException.class,
                () -> fundingService.fund(command(AMOUNT, KEY))
        );

        verify(bankTransferGateway, never()).preflight(any());
        verify(bankTransferGateway, never()).withdraw(any());
        verify(walletMapper).getWalletSnapshotForUpdateByWalletId(WALLET_ID);
        verify(walletMapper, never()).updateWalletBalanceByWalletId(any());
    }

    @Test
    @DisplayName("UNIQUE 충돌 후 주문 행을 찾지 못하면 재시도 가능한 잠금 오류로 분류한다")
    void duplicateWithoutVisibleClaimRequiresRetry() {
        when(fundingMapper.insertFundingOrder(any()))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(fundingMapper.findByIdempotencyKeyForShare(KEY)).thenReturn(null);

        assertThrows(
                CannotAcquireLockException.class,
                () -> fundingService.fund(command(AMOUNT, KEY))
        );
        verify(transactionExecutor, times(3)).execute(any());
        verify(fundingMapper, times(3)).insertFundingOrder(any());
    }

    @Test
    @DisplayName("완료되지 않은 주문은 기존 요청이라도 다시 이체하지 않는다")
    void incompleteClaimCannotBeReplayed() {
        FundingOrder existing = completedOrder(AMOUNT).toBuilder()
                .transferredAmount(null)
                .mockBankTransactionId(null)
                .status("READY")
                .build();
        when(fundingMapper.insertFundingOrder(any()))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(fundingMapper.findByIdempotencyKeyForShare(KEY)).thenReturn(existing);

        assertThrows(
                FundingIntegrityException.class,
                () -> fundingService.fund(command(AMOUNT, KEY))
        );

        verify(bankTransferGateway, never()).preflight(any());
        verify(bankTransferGateway, never()).withdraw(any());
        verify(walletMapper).getWalletSnapshotForUpdateByWalletId(WALLET_ID);
        verify(walletMapper, never()).updateWalletBalanceByWalletId(any());
    }

    @Test
    @DisplayName("재응답할 충전 원장 스냅샷이 없으면 저장 결과를 거부한다")
    void replayRejectsMissingLedgerSnapshot() {
        when(fundingMapper.insertFundingOrder(any()))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(fundingMapper.findByIdempotencyKeyForShare(KEY))
                .thenReturn(completedOrder(AMOUNT));
        when(walletMapper.findFundingTransactionSnapshot(
                ORDER_ID, WALLET_ID, WalletIdempotencyKeys.funding(KEY)))
                .thenReturn(null);

        assertThrows(
                FundingIntegrityException.class,
                () -> fundingService.fund(command(AMOUNT, KEY))
        );

        verify(walletMapper, never()).getAvailableBalance(anyLong());
        verify(walletMapper, never()).getLockedBalance(anyLong());
    }

    @Test
    @DisplayName("게이트웨이 사전 소유권 검증 실패 시 지갑과 계좌 이체를 시작하지 않는다")
    void preflightFailureStopsMoneyMovement() {
        when(fundingMapper.insertFundingOrder(any())).thenAnswer(invocation -> {
            FundingOrderParam param = invocation.getArgument(0);
            param.setId(ORDER_ID);
            return 1;
        });
        org.mockito.Mockito.doThrow(new BankAccountForbiddenException("forbidden"))
                .when(bankTransferGateway)
                .preflight(any(BankAccountPreflightCommand.class));

        assertThrows(
                BankAccountForbiddenException.class,
                () -> fundingService.fund(command(AMOUNT, KEY))
        );

        verify(walletMapper).getWalletSnapshotForUpdateByWalletId(WALLET_ID);
        verify(bankTransferGateway, never()).withdraw(any());
    }

    @Test
    @DisplayName("존재하지 않는 계좌 FK 오류는 게이트웨이의 계좌 권한 오류로 변환한다")
    void invalidAccountReferenceIsTranslatedByGateway() {
        when(fundingMapper.insertFundingOrder(any()))
                .thenThrow(new DataIntegrityViolationException("foreign key"));
        org.mockito.Mockito.doThrow(new BankAccountForbiddenException("forbidden"))
                .when(bankTransferGateway)
                .preflight(any(BankAccountPreflightCommand.class));

        assertThrows(
                BankAccountForbiddenException.class,
                () -> fundingService.fund(command(AMOUNT, KEY))
        );

        verify(walletMapper).getWalletSnapshotForUpdateByWalletId(WALLET_ID);
        verify(bankTransferGateway, never()).withdraw(any());
    }

    @Test
    @DisplayName("손상된 지갑 스냅샷은 계좌 출금 전 서버 무결성 오류로 거부한다")
    void corruptedWalletSnapshotStopsBankWithdrawal() {
        WalletBalanceSnapshot corrupted = wallet(50L, 700_000L, 20_000L).toBuilder()
                .userId(EMPLOYER_ID + 1)
                .build();
        when(walletMapper.getWalletSnapshotForUpdateByWalletId(WALLET_ID))
                .thenReturn(corrupted);

        assertThrows(
                FundingIntegrityException.class,
                () -> fundingService.fund(command(AMOUNT, KEY))
        );

        verify(bankTransferGateway, never()).withdraw(any());
        verify(fundingMapper, never()).insertFundingOrder(any());
        verify(walletMapper, never()).updateWalletBalanceByWalletId(any());
    }

    @Test
    @DisplayName("0 이하 충전 금액은 DB 접근 전에 요청 오류로 거부한다")
    void invalidAmountIsRejectedBeforeClaim() {
        assertThrows(
                InvalidFundingRequestException.class,
                () -> fundingService.fund(command(0L, KEY))
        );

        verifyNoInteractions(
                fundingMapper, walletMapper, bankTransferGateway, transactionExecutor);
    }

    @Test
    @DisplayName("잠금 충돌 후에는 새 시도로 충전을 완료한다")
    void retryableLockFailureStartsAnotherAttempt() {
        when(walletMapper.getWalletSnapshotForUpdateByWalletId(WALLET_ID))
                .thenThrow(new CannotAcquireLockException("lock"))
                .thenReturn(wallet(50L, 700_000L, 20_000L));
        when(fundingMapper.insertFundingOrder(any()))
                .thenAnswer(invocation -> {
                    FundingOrderParam param = invocation.getArgument(0);
                    param.setId(ORDER_ID);
                    return 1;
                });
        when(bankTransferGateway.withdraw(any())).thenReturn(successfulTransfer());
        when(fundingMapper.completeFundingOrder(
                ORDER_ID, AMOUNT, BANK_TRANSACTION_ID)).thenReturn(1);
        when(walletMapper.updateWalletBalanceByWalletId(any())).thenReturn(1);
        when(walletMapper.insertWalletTransaction(any())).thenReturn(1);

        FundingResult result = fundingService.fund(command(AMOUNT, KEY));

        assertFalse(result.isReplayed());
        verify(transactionExecutor, times(2)).execute(any());
        verify(walletMapper, times(2)).getWalletSnapshotForUpdateByWalletId(WALLET_ID);
        verify(fundingMapper).insertFundingOrder(any());
        verify(bankTransferGateway).withdraw(any());
    }

    @Test
    @DisplayName("예상하지 않은 지갑 원장 중복은 잠금 오류처럼 재시도하지 않는다")
    void downstreamDuplicateIsNotRetried() {
        stubNewFundingUntilTransfer(successfulTransfer());
        when(fundingMapper.completeFundingOrder(
                ORDER_ID, AMOUNT, BANK_TRANSACTION_ID)).thenReturn(1);
        when(walletMapper.updateWalletBalanceByWalletId(any())).thenReturn(1);
        when(walletMapper.insertWalletTransaction(any()))
                .thenThrow(new DuplicateKeyException("ledger duplicate"));

        assertThrows(
                DuplicateKeyException.class,
                () -> fundingService.fund(command(AMOUNT, KEY))
        );

        verify(transactionExecutor).execute(any());
        verify(fundingMapper).insertFundingOrder(any());
        verify(bankTransferGateway).withdraw(any());
    }

    @Test
    @DisplayName("게이트웨이 결과가 null이면 지갑을 충전하지 않는다")
    void nullGatewayResultStopsWalletUpdate() {
        stubNewFundingUntilTransfer(null);

        assertInvalidGatewayResult();
    }

    @Test
    @DisplayName("게이트웨이 성공 금액이 요청과 다르면 지갑을 충전하지 않는다")
    void mismatchedGatewayAmountStopsWalletUpdate() {
        BankTransferResult transfer = successfulTransfer();
        transfer = BankTransferResult.builder()
                .bankTransactionId(transfer.getBankTransactionId())
                .bankTranId(transfer.getBankTranId())
                .status(transfer.getStatus())
                .transferredAmount(AMOUNT - 1)
                .balanceBefore(transfer.getBalanceBefore())
                .balanceAfter(transfer.getBalanceAfter())
                .build();
        stubNewFundingUntilTransfer(transfer);

        assertInvalidGatewayResult();
    }

    @Test
    @DisplayName("게이트웨이 거래 식별자가 없으면 지갑을 충전하지 않는다")
    void missingGatewayIdentityStopsWalletUpdate() {
        stubNewFundingUntilTransfer(BankTransferResult.builder()
                .bankTranId(" ")
                .status("SUCCESS")
                .transferredAmount(AMOUNT)
                .balanceBefore(AMOUNT)
                .balanceAfter(0L)
                .build());

        assertInvalidGatewayResult();
    }

    @Test
    @DisplayName("게이트웨이 잔액 증감이 이체 금액과 다르면 지갑을 충전하지 않는다")
    void invalidGatewayBalanceDeltaStopsWalletUpdate() {
        stubNewFundingUntilTransfer(BankTransferResult.builder()
                .bankTransactionId(BANK_TRANSACTION_ID)
                .bankTranId("M123")
                .status("SUCCESS")
                .transferredAmount(AMOUNT)
                .balanceBefore(AMOUNT)
                .balanceAfter(1L)
                .build());

        assertInvalidGatewayResult();
    }

    @Test
    @DisplayName("재응답 원장의 available 증감이 주문 금액과 다르면 거부한다")
    void replayRejectsInvalidAvailableDelta() {
        when(fundingMapper.insertFundingOrder(any()))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(fundingMapper.findByIdempotencyKeyForShare(KEY))
                .thenReturn(completedOrder(AMOUNT));
        WalletTransactionSnapshot snapshot =
                fundingSnapshot(1_000_000L, 20_000L, AMOUNT).toBuilder()
                        .availableBefore(999_999L)
                        .build();
        when(walletMapper.findFundingTransactionSnapshot(
                ORDER_ID, WALLET_ID, WalletIdempotencyKeys.funding(KEY)))
                .thenReturn(snapshot);

        assertThrows(
                FundingIntegrityException.class,
                () -> fundingService.fund(command(AMOUNT, KEY))
        );
    }

    @Test
    @DisplayName("재응답 원장에서 잠금 잔액이 바뀌었으면 거부한다")
    void replayRejectsChangedLockedBalance() {
        when(fundingMapper.insertFundingOrder(any()))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(fundingMapper.findByIdempotencyKeyForShare(KEY))
                .thenReturn(completedOrder(AMOUNT));
        WalletTransactionSnapshot snapshot =
                fundingSnapshot(1_000_000L, 20_000L, AMOUNT).toBuilder()
                        .lockedBefore(19_999L)
                        .build();
        when(walletMapper.findFundingTransactionSnapshot(
                ORDER_ID, WALLET_ID, WalletIdempotencyKeys.funding(KEY)))
                .thenReturn(snapshot);

        assertThrows(
                FundingIntegrityException.class,
                () -> fundingService.fund(command(AMOUNT, KEY))
        );
    }

    private void stubNewFundingUntilTransfer(BankTransferResult transfer) {
        when(fundingMapper.insertFundingOrder(any())).thenAnswer(invocation -> {
            FundingOrderParam param = invocation.getArgument(0);
            param.setId(ORDER_ID);
            return 1;
        });
        when(walletMapper.getWalletSnapshotForUpdateByWalletId(WALLET_ID))
                .thenReturn(wallet(50L, 700_000L, 20_000L));
        when(bankTransferGateway.withdraw(any())).thenReturn(transfer);
    }

    private void assertInvalidGatewayResult() {
        assertThrows(
                BankTransferIntegrityException.class,
                () -> fundingService.fund(command(AMOUNT, KEY))
        );

        verify(fundingMapper, never()).completeFundingOrder(anyLong(), anyLong(), anyLong());
        verify(walletMapper, never()).updateWalletBalanceByWalletId(any());
        verify(walletMapper, never()).insertWalletTransaction(any());
    }

    private FundingCommand command(Long amount, String key) {
        return FundingCommand.builder()
                .employerId(EMPLOYER_ID)
                .bankCode(BANK_CODE)
                .accountNo(ACCOUNT_NO)
                .pin(PIN)
                .amount(amount)
                .idempotencyKey(key)
                .build();
    }

    private WalletBalanceSnapshot wallet(
            Long walletId, Long availableBalance, Long lockedBalance) {
        return WalletBalanceSnapshot.builder()
                .walletId(walletId)
                .userId(EMPLOYER_ID)
                .availableBalance(availableBalance)
                .lockedBalance(lockedBalance)
                .build();
    }

    private BankTransferResult successfulTransfer() {
        return BankTransferResult.builder()
                .bankTransactionId(BANK_TRANSACTION_ID)
                .bankTranId("M123")
                .status("SUCCESS")
                .transferredAmount(AMOUNT)
                .balanceBefore(AMOUNT)
                .balanceAfter(0L)
                .build();
    }

    private FundingOrder completedOrder(Long amount) {
        return FundingOrder.builder()
                .id(ORDER_ID)
                .employerId(EMPLOYER_ID)
                .linkedAccountId(ACCOUNT_ID)
                .expectedAmount(amount)
                .transferredAmount(amount)
                .mockBankTransactionId(BANK_TRANSACTION_ID)
                .status("COMPLETED")
                .build();
    }

    private WalletTransactionSnapshot fundingSnapshot(
            Long availableAfter, Long lockedAfter, Long amount) {
        return WalletTransactionSnapshot.builder()
                .id(41L)
                .walletId(50L)
                .walletUserId(EMPLOYER_ID)
                .transactionType("FUNDING")
                .amount(amount)
                .availableBefore(availableAfter - amount)
                .availableAfter(availableAfter)
                .lockedBefore(lockedAfter)
                .lockedAfter(lockedAfter)
                .referenceType("FUNDING_ORDER")
                .referenceId(ORDER_ID)
                .build();
    }
}
