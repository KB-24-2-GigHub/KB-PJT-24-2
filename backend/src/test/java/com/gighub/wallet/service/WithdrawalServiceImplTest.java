package com.gighub.wallet.service;

import com.gighub.bank.exception.BankAccountForbiddenException;
import com.gighub.bank.exception.BankTransferIntegrityException;
import com.gighub.bank.service.BankAccountPreflightCommand;
import com.gighub.bank.service.BankTransferCommand;
import com.gighub.bank.service.BankTransferGateway;
import com.gighub.bank.service.BankTransferResult;
import com.gighub.wallet.domain.Money;
import com.gighub.wallet.dto.WalletBalanceSnapshot;
import com.gighub.wallet.dto.WalletTransactionSnapshot;
import com.gighub.wallet.dto.WithdrawalOrder;
import com.gighub.wallet.exception.IdempotencyKeyReusedException;
import com.gighub.wallet.exception.InsufficientAvailableBalanceException;
import com.gighub.wallet.exception.InvalidWithdrawalRequestException;
import com.gighub.wallet.exception.WithdrawalIntegrityException;
import com.gighub.wallet.mapper.WalletMapper;
import com.gighub.wallet.mapper.WithdrawalMapper;
import com.gighub.wallet.mapper.param.WalletBalanceUpdateParam;
import com.gighub.wallet.mapper.param.WalletTransactionParam;
import com.gighub.wallet.mapper.param.WithdrawalOrderParam;
import com.gighub.wallet.service.command.WithdrawalCommand;
import com.gighub.wallet.service.impl.WithdrawalServiceImpl;
import com.gighub.wallet.service.impl.WithdrawalTransactionExecutor;
import com.gighub.wallet.service.result.WithdrawalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WithdrawalServiceImplTest {

    private static final Long USER_ID = 4L;
    private static final Long ACCOUNT_ID = 20L;
    private static final String BANK_CODE = "004";
    private static final String ACCOUNT_NO = "1234567890";
    private static final Long WALLET_ID = 40L;
    private static final Long AMOUNT = 300_000L;
    private static final Long REQUEST_ID = 22L;
    private static final Long BANK_TRANSACTION_ID = 33L;
    private static final String KEY = "WITHDRAWAL-TEST-001";

    @Mock
    private WithdrawalMapper withdrawalMapper;

    @Mock
    private WalletMapper walletMapper;

    @Mock
    private BankTransferGateway bankTransferGateway;

    @Mock
    private WithdrawalTransactionExecutor transactionExecutor;

    @InjectMocks
    private WithdrawalServiceImpl withdrawalService;

    @BeforeEach
    void executeAttemptInsideTestTransactionBoundary() {
        Mockito.lenient()
                .when(transactionExecutor.execute(any()))
                .thenAnswer(invocation -> {
                    Supplier<WithdrawalResult> attempt = invocation.getArgument(0);
                    return attempt.get();
                });
        Mockito.lenient()
                .when(bankTransferGateway.resolveAccountId(BANK_CODE, ACCOUNT_NO))
                .thenReturn(ACCOUNT_ID);
    }

    @Test
    @DisplayName("각 출금 재시도는 반드시 새로운 트랜잭션에서 실행한다")
    void withdrawalAttemptRequiresNewTransaction() throws NoSuchMethodException {
        Transactional annotation = WithdrawalTransactionExecutor.class
                .getMethod("execute", Supplier.class)
                .getAnnotation(Transactional.class);

        assertEquals(Propagation.REQUIRES_NEW, annotation.propagation());
    }

    @Test
    @DisplayName("출금은 지갑을 잠근 뒤 요청을 선점하고 계좌를 처리한다")
    void withdrawalLocksWalletThenClaimsBeforeAccountAccess() {
        stubClaim();
        when(walletMapper.getWalletSnapshotForUpdateByWalletId(anyLong()))
                .thenReturn(wallet(500_000L, 20_000L));
        when(bankTransferGateway.deposit(any())).thenReturn(successfulTransfer());
        when(withdrawalMapper.completeWithdrawalRequest(anyLong(), anyLong()))
                .thenReturn(1);
        when(walletMapper.updateWalletBalanceByWalletId(any())).thenReturn(1);
        when(walletMapper.insertWalletTransaction(any())).thenReturn(1);

        WithdrawalResult result = withdrawalService.withdraw(command(AMOUNT, KEY));

        assertEquals(REQUEST_ID, result.getWithdrawalRequestId());
        assertEquals("COMPLETED", result.getStatus());
        assertEquals(BANK_TRANSACTION_ID, result.getBankTransactionId());
        assertFalse(result.isReplayed());

        ArgumentCaptor<WithdrawalOrderParam> claimCaptor =
                ArgumentCaptor.forClass(WithdrawalOrderParam.class);
        verify(withdrawalMapper).insertWithdrawalRequest(claimCaptor.capture());
        assertNotNull(claimCaptor.getValue().getIdempotencyKey());

        // 호출 순서를 계약으로 고정해 내부 재시도가 잠금 순서 회귀를 가리지 않도록 한다.
        InOrder order = inOrder(walletMapper, withdrawalMapper, bankTransferGateway);
        order.verify(walletMapper).resolveWalletId(USER_ID, Money.KRW);
        order.verify(walletMapper).getWalletSnapshotForUpdateByWalletId(WALLET_ID);
        order.verify(withdrawalMapper).insertWithdrawalRequest(any());
        order.verify(bankTransferGateway).preflight(any(BankAccountPreflightCommand.class));
        order.verify(bankTransferGateway).deposit(any(BankTransferCommand.class));
    }

    @Test
    @DisplayName("출금 원장은 available만 줄이고 잠금 잔액은 그대로 둔다")
    void withdrawalLedgerKeepsLockedBalance() {
        stubClaim();
        when(walletMapper.getWalletSnapshotForUpdateByWalletId(anyLong()))
                .thenReturn(wallet(500_000L, 20_000L));
        when(bankTransferGateway.deposit(any())).thenReturn(successfulTransfer());
        when(withdrawalMapper.completeWithdrawalRequest(anyLong(), anyLong()))
                .thenReturn(1);
        when(walletMapper.updateWalletBalanceByWalletId(any())).thenReturn(1);
        when(walletMapper.insertWalletTransaction(any())).thenReturn(1);

        withdrawalService.withdraw(command(AMOUNT, KEY));

        ArgumentCaptor<WalletBalanceUpdateParam> balanceCaptor =
                ArgumentCaptor.forClass(WalletBalanceUpdateParam.class);
        verify(walletMapper).updateWalletBalanceByWalletId(balanceCaptor.capture());
        WalletBalanceUpdateParam balanceUpdate = balanceCaptor.getValue();
        assertEquals(WALLET_ID, balanceUpdate.getWalletId());
        assertEquals(Money.KRW, balanceUpdate.getCurrency());
        assertEquals(500_000L, balanceUpdate.getAvailableBefore());
        assertEquals(200_000L, balanceUpdate.getAvailableAfter());
        assertEquals(20_000L, balanceUpdate.getLockedBefore());
        assertEquals(20_000L, balanceUpdate.getLockedAfter());

        ArgumentCaptor<WalletTransactionParam> captor =
                ArgumentCaptor.forClass(WalletTransactionParam.class);
        verify(walletMapper).insertWalletTransaction(captor.capture());

        WalletTransactionParam ledger = captor.getValue();
        assertEquals(WALLET_ID, ledger.getWalletId());
        assertEquals("WITHDRAWAL", ledger.getTransactionType());
        assertEquals(500_000L, ledger.getAvailableBefore());
        assertEquals(200_000L, ledger.getAvailableAfter());
        assertEquals(20_000L, ledger.getLockedBefore());
        assertEquals(20_000L, ledger.getLockedAfter());
        assertEquals("WITHDRAWAL_REQUEST", ledger.getReferenceType());
        assertEquals(REQUEST_ID, ledger.getReferenceId());
        assertNotNull(ledger.getIdempotencyKey());
    }

    @Test
    @DisplayName("잠금 잔액은 출금할 수 없으므로 가용 잔액만으로 판단한다")
    void withdrawalRejectsWhenAvailableBalanceIsShort() {
        stubClaim();
        when(walletMapper.getWalletSnapshotForUpdateByWalletId(anyLong()))
                .thenReturn(wallet(AMOUNT - 1, 1_000_000L));

        assertThrows(
                InsufficientAvailableBalanceException.class,
                () -> withdrawalService.withdraw(command(AMOUNT, KEY))
        );

        verify(withdrawalMapper).insertWithdrawalRequest(any());
        verify(bankTransferGateway, never()).preflight(any());
        verify(bankTransferGateway, never()).deposit(any());
        verify(withdrawalMapper, never()).completeWithdrawalRequest(anyLong(), anyLong());
        verify(walletMapper, never()).updateWalletBalanceByWalletId(any());
        verify(walletMapper, never()).insertWalletTransaction(any());
    }

    @Test
    @DisplayName("동일한 동시 출금 요청은 UNIQUE 충돌 후 저장 원장을 재응답한다")
    void duplicateSameRequestReplaysStoredLedger() {
        stubClaim();
        when(walletMapper.getWalletSnapshotForUpdateByWalletId(anyLong())).thenReturn(wallet(500_000L, 20_000L));
        when(withdrawalMapper.insertWithdrawalRequest(any()))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(withdrawalMapper.findByIdempotencyKeyForShare(anyString()))
                .thenReturn(completedOrder(AMOUNT));
        when(walletMapper.findTransactionByIdempotencyKey(anyString()))
                .thenReturn(withdrawalSnapshot(AMOUNT));

        WithdrawalResult result = withdrawalService.withdraw(command(AMOUNT, KEY));

        assertTrue(result.isReplayed());
        assertEquals(REQUEST_ID, result.getWithdrawalRequestId());
        assertEquals(BANK_TRANSACTION_ID, result.getBankTransactionId());

        verify(bankTransferGateway, never()).preflight(any());
        verify(bankTransferGateway, never()).deposit(any());
        verify(walletMapper, never()).updateWalletBalanceByWalletId(any());
        verify(walletMapper, never()).insertWalletTransaction(any());
    }

    @Test
    @DisplayName("완료된 동일 키 출금은 현재 잔액과 계좌 상태를 다시 검증하지 않는다")
    void duplicateSameRequestReplaysWithoutCurrentStateValidation() {
        stubClaim();
        when(walletMapper.getWalletSnapshotForUpdateByWalletId(anyLong()))
                .thenReturn(wallet(0L, 20_000L));
        when(withdrawalMapper.insertWithdrawalRequest(any()))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(withdrawalMapper.findByIdempotencyKeyForShare(anyString()))
                .thenReturn(completedOrder(AMOUNT));
        when(walletMapper.findTransactionByIdempotencyKey(anyString()))
                .thenReturn(withdrawalSnapshot(AMOUNT));
        Mockito.doThrow(new BankAccountForbiddenException("blocked"))
                .when(bankTransferGateway)
                .preflight(any(BankAccountPreflightCommand.class));

        WithdrawalResult result = withdrawalService.withdraw(command(AMOUNT, KEY));

        assertTrue(result.isReplayed());
        assertEquals(REQUEST_ID, result.getWithdrawalRequestId());
        verify(bankTransferGateway, never()).preflight(any());
        verify(bankTransferGateway, never()).deposit(any());
        verify(withdrawalMapper, never()).completeWithdrawalRequest(anyLong(), anyLong());
        verify(walletMapper, never()).updateWalletBalanceByWalletId(any());
        verify(walletMapper, never()).insertWalletTransaction(any());
    }

    @Test
    @DisplayName("같은 멱등 키의 요청 본문이 다르면 출금하지 않는다")
    void duplicateDifferentRequestIsRejected() {
        stubClaim();
        when(walletMapper.getWalletSnapshotForUpdateByWalletId(anyLong())).thenReturn(wallet(500_000L, 20_000L));
        when(withdrawalMapper.insertWithdrawalRequest(any()))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(withdrawalMapper.findByIdempotencyKeyForShare(anyString()))
                .thenReturn(completedOrder(AMOUNT + 1));

        assertThrows(
                IdempotencyKeyReusedException.class,
                () -> withdrawalService.withdraw(command(AMOUNT, KEY))
        );

        verify(bankTransferGateway, never()).deposit(any());
    }

    @Test
    @DisplayName("UNIQUE 충돌 후 요청 행을 찾지 못하면 재시도 가능한 잠금 오류로 분류한다")
    void duplicateWithoutVisibleClaimRequiresRetry() {
        stubClaim();
        when(walletMapper.getWalletSnapshotForUpdateByWalletId(anyLong())).thenReturn(wallet(500_000L, 20_000L));
        when(withdrawalMapper.insertWithdrawalRequest(any()))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(withdrawalMapper.findByIdempotencyKeyForShare(anyString())).thenReturn(null);

        assertThrows(
                CannotAcquireLockException.class,
                () -> withdrawalService.withdraw(command(AMOUNT, KEY))
        );

        verify(transactionExecutor, times(3)).execute(any());
    }

    @Test
    @DisplayName("완료되지 않은 요청은 기존 요청이라도 다시 이체하지 않는다")
    void incompleteClaimCannotBeReplayed() {
        WithdrawalOrder existing = completedOrder(AMOUNT).toBuilder()
                .mockBankTransactionId(null)
                .status("READY")
                .build();

        stubClaim();
        when(walletMapper.getWalletSnapshotForUpdateByWalletId(anyLong())).thenReturn(wallet(500_000L, 20_000L));
        when(withdrawalMapper.insertWithdrawalRequest(any()))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(withdrawalMapper.findByIdempotencyKeyForShare(anyString())).thenReturn(existing);

        assertThrows(
                WithdrawalIntegrityException.class,
                () -> withdrawalService.withdraw(command(AMOUNT, KEY))
        );

        verify(bankTransferGateway, never()).deposit(any());
    }

    @Test
    @DisplayName("재응답 원장의 available 감소가 요청 금액과 다르면 거부한다")
    void replayRejectsInvalidAvailableDelta() {
        stubClaim();
        when(walletMapper.getWalletSnapshotForUpdateByWalletId(anyLong())).thenReturn(wallet(500_000L, 20_000L));
        when(withdrawalMapper.insertWithdrawalRequest(any()))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(withdrawalMapper.findByIdempotencyKeyForShare(anyString()))
                .thenReturn(completedOrder(AMOUNT));

        WalletTransactionSnapshot base = withdrawalSnapshot(AMOUNT);
        WalletTransactionSnapshot snapshot = base.toBuilder()
                .availableAfter(base.getAvailableAfter() + 1)
                .build();
        when(walletMapper.findTransactionByIdempotencyKey(anyString())).thenReturn(snapshot);

        assertThrows(
                WithdrawalIntegrityException.class,
                () -> withdrawalService.withdraw(command(AMOUNT, KEY))
        );
    }

    @Test
    @DisplayName("재응답 원장에서 잠금 잔액이 바뀌었으면 거부한다")
    void replayRejectsChangedLockedBalance() {
        stubClaim();
        when(walletMapper.getWalletSnapshotForUpdateByWalletId(anyLong())).thenReturn(wallet(500_000L, 20_000L));
        when(withdrawalMapper.insertWithdrawalRequest(any()))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(withdrawalMapper.findByIdempotencyKeyForShare(anyString()))
                .thenReturn(completedOrder(AMOUNT));

        WalletTransactionSnapshot base = withdrawalSnapshot(AMOUNT);
        WalletTransactionSnapshot snapshot = base.toBuilder()
                .lockedAfter(base.getLockedBefore() + 1)
                .build();
        when(walletMapper.findTransactionByIdempotencyKey(anyString())).thenReturn(snapshot);

        assertThrows(
                WithdrawalIntegrityException.class,
                () -> withdrawalService.withdraw(command(AMOUNT, KEY))
        );
    }

    @Test
    @DisplayName("신규 출금의 계좌 사전 검증 실패는 지갑 잠금과 claim 이후 이체를 중단한다")
    void preflightFailureAfterClaimStopsMoneyMovement() {
        stubClaim();
        // 순서 변경 반영: 지갑은 정상 잠기지만(Lock), Preflight에서 예외가 터져 이체는 중단됨
        when(walletMapper.getWalletSnapshotForUpdateByWalletId(anyLong()))
                .thenReturn(wallet(500_000L, 20_000L));
        Mockito.doThrow(new BankAccountForbiddenException("forbidden"))
                .when(bankTransferGateway)
                .preflight(any(BankAccountPreflightCommand.class));

        assertThrows(
                BankAccountForbiddenException.class,
                () -> withdrawalService.withdraw(command(AMOUNT, KEY))
        );

        InOrder order = inOrder(walletMapper, withdrawalMapper, bankTransferGateway);
        order.verify(walletMapper).resolveWalletId(USER_ID, Money.KRW);
        order.verify(walletMapper).getWalletSnapshotForUpdateByWalletId(WALLET_ID);
        order.verify(withdrawalMapper).insertWithdrawalRequest(any());
        order.verify(bankTransferGateway).preflight(any(BankAccountPreflightCommand.class));

        verify(walletMapper, never()).updateWalletBalanceByWalletId(any());
        verify(bankTransferGateway, never()).deposit(any());
    }

    @Test
    @DisplayName("게이트웨이 입금 잔액 증감이 요청 금액과 다르면 지갑을 차감하지 않는다")
    void invalidGatewayBalanceDeltaStopsWalletUpdate() {
        stubClaim();
        when(walletMapper.getWalletSnapshotForUpdateByWalletId(anyLong()))
                .thenReturn(wallet(500_000L, 20_000L));
        when(bankTransferGateway.deposit(any())).thenReturn(BankTransferResult.builder()
                .bankTransactionId(BANK_TRANSACTION_ID)
                .bankTranId("M123")
                .status("SUCCESS")
                .transferredAmount(AMOUNT)
                .balanceBefore(0L)
                .balanceAfter(1L)
                .build());

        assertThrows(
                BankTransferIntegrityException.class,
                () -> withdrawalService.withdraw(command(AMOUNT, KEY))
        );

        verify(withdrawalMapper, never())
                .completeWithdrawalRequest(anyLong(), anyLong());
        verify(walletMapper, never()).updateWalletBalanceByWalletId(any());
        verify(walletMapper, never()).insertWalletTransaction(any());
    }

    @Test
    @DisplayName("게이트웨이 성공 상태가 아니면 지갑을 차감하지 않는다")
    void nonSuccessGatewayStatusStopsWalletUpdate() {
        stubClaim();
        when(walletMapper.getWalletSnapshotForUpdateByWalletId(anyLong()))
                .thenReturn(wallet(500_000L, 20_000L));
        when(bankTransferGateway.deposit(any())).thenReturn(BankTransferResult.builder()
                .bankTransactionId(BANK_TRANSACTION_ID)
                .bankTranId("M123")
                .status("FAILED")
                .transferredAmount(AMOUNT)
                .balanceBefore(0L)
                .balanceAfter(AMOUNT)
                .build());

        assertThrows(
                BankTransferIntegrityException.class,
                () -> withdrawalService.withdraw(command(AMOUNT, KEY))
        );

        verify(walletMapper, never()).updateWalletBalanceByWalletId(any());
    }

    @Test
    @DisplayName("잠금 지갑의 차감 UPDATE가 0건이면 서버 무결성 오류다")
    void unexpectedWalletUpdateCountIsIntegrityFailure() {
        stubClaim();
        when(walletMapper.getWalletSnapshotForUpdateByWalletId(anyLong()))
                .thenReturn(wallet(500_000L, 20_000L));
        when(bankTransferGateway.deposit(any())).thenReturn(successfulTransfer());
        when(withdrawalMapper.completeWithdrawalRequest(anyLong(), anyLong()))
                .thenReturn(1);
        when(walletMapper.updateWalletBalanceByWalletId(any())).thenReturn(0);

        assertThrows(
                WithdrawalIntegrityException.class,
                () -> withdrawalService.withdraw(command(AMOUNT, KEY))
        );

        verify(walletMapper, never()).insertWalletTransaction(any());
    }

    @Test
    @DisplayName("손상된 지갑 스냅샷은 계좌 입금 전 서버 무결성 오류로 거부한다")
    void corruptedWalletSnapshotStopsBankDeposit() {
        stubClaim();
        WalletBalanceSnapshot corrupted = wallet(500_000L, 20_000L).toBuilder()
                .userId(USER_ID + 1) // 고의로 ID 불일치 발생
                .build();
        when(walletMapper.getWalletSnapshotForUpdateByWalletId(anyLong())).thenReturn(corrupted);

        assertThrows(
                WithdrawalIntegrityException.class,
                () -> withdrawalService.withdraw(command(AMOUNT, KEY))
        );

        verify(bankTransferGateway, never()).deposit(any());
    }

    @Test
    @DisplayName("0 이하 출금 금액은 DB 접근 전에 요청 오류로 거부한다")
    void invalidAmountIsRejectedBeforeClaim() {
        assertThrows(
                InvalidWithdrawalRequestException.class,
                () -> withdrawalService.withdraw(command(0L, KEY))
        );

        Mockito.verifyNoInteractions(
                withdrawalMapper, walletMapper, bankTransferGateway, transactionExecutor);
    }

    private void stubClaim() {
        Mockito.lenient().when(walletMapper.resolveWalletId(anyLong(), anyString())).thenReturn(WALLET_ID);
        Mockito.lenient().when(withdrawalMapper.insertWithdrawalRequest(any())).thenAnswer(invocation -> {
            WithdrawalOrderParam param = invocation.getArgument(0);
            // NPE 수정: Mockito가 when() 내부에서 인자를 null로 던질 때 방어
            if (param != null) {
                param.setId(REQUEST_ID);
            }
            return 1;
        });
    }

    private WithdrawalCommand command(Long amount, String key) {
        return WithdrawalCommand.builder()
                .userId(USER_ID)
                .bankCode(BANK_CODE)
                .accountNo(ACCOUNT_NO)
                .amount(amount)
                .idempotencyKey(key)
                .build();
    }

    private WalletBalanceSnapshot wallet(Long availableBalance, Long lockedBalance) {
        return WalletBalanceSnapshot.builder()
                .walletId(WALLET_ID)
                .userId(USER_ID)
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
                .balanceBefore(0L)
                .balanceAfter(AMOUNT)
                .build();
    }

    private WithdrawalOrder completedOrder(Long amount) {
        return WithdrawalOrder.builder()
                .id(REQUEST_ID)
                .userId(USER_ID)
                .walletId(WALLET_ID)
                .linkedAccountId(ACCOUNT_ID)
                .amount(amount)
                .mockBankTransactionId(BANK_TRANSACTION_ID)
                .status("COMPLETED")
                .build();
    }

    private WalletTransactionSnapshot withdrawalSnapshot(Long amount) {
        return WalletTransactionSnapshot.builder()
                .id(42L)
                .walletId(WALLET_ID)
                .walletUserId(USER_ID)
                .workCaseId(null)
                .transactionType("WITHDRAWAL")
                .amount(amount)
                .availableBefore(500_000L)
                .availableAfter(500_000L - amount)
                .lockedBefore(20_000L)
                .lockedAfter(20_000L)
                .referenceType("WITHDRAWAL_REQUEST")
                .referenceId(REQUEST_ID)
                .build();
    }
}
