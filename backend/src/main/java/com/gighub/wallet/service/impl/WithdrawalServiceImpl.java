package com.gighub.wallet.service.impl;

import com.gighub.bank.exception.BankTransferIntegrityException;
import com.gighub.bank.service.BankAccountPreflightCommand;
import com.gighub.bank.service.BankTransferCommand;
import com.gighub.bank.service.BankTransferGateway;
import com.gighub.bank.service.BankTransferResult;
import com.gighub.wallet.domain.Money;
import com.gighub.wallet.domain.WalletBalance;
import com.gighub.wallet.dto.WalletBalanceSnapshot;
import com.gighub.wallet.dto.WalletTransactionSnapshot;
import com.gighub.wallet.dto.WithdrawalOrder;
import com.gighub.wallet.exception.IdempotencyKeyReusedException;
import com.gighub.wallet.exception.InsufficientAvailableBalanceException;
import com.gighub.wallet.exception.InvalidWalletStateException;
import com.gighub.wallet.exception.InvalidWithdrawalRequestException;
import com.gighub.wallet.exception.WithdrawalIntegrityException;
import com.gighub.wallet.idempotency.WalletIdempotencyKeys;
import com.gighub.wallet.mapper.WalletMapper;
import com.gighub.wallet.mapper.WithdrawalMapper;
import com.gighub.wallet.mapper.param.WalletBalanceUpdateParam;
import com.gighub.wallet.mapper.param.WalletTransactionParam;
import com.gighub.wallet.mapper.param.WithdrawalOrderParam;
import com.gighub.wallet.service.WithdrawalService;
import com.gighub.wallet.service.command.WithdrawalCommand;
import com.gighub.wallet.service.result.WithdrawalResult;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class WithdrawalServiceImpl implements WithdrawalService {
    private static final int MAX_TRANSACTION_ATTEMPTS = 3;
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String TRANSFER_SUCCESS = "SUCCESS";
    private static final String TX_WITHDRAWAL = "WITHDRAWAL";
    private static final String REF_WITHDRAWAL_REQUEST = "WITHDRAWAL_REQUEST";

    private final WithdrawalMapper withdrawalMapper;
    private final WalletMapper walletMapper;
    private final BankTransferGateway bankTransferGateway;
    private final WithdrawalTransactionExecutor transactionExecutor;

    @Override
    public WithdrawalResult withdraw(WithdrawalCommand command) {
        validateCommand(command);
        String rawKey = WalletIdempotencyKeys.validateRawKey(command.getIdempotencyKey());
        String ledgerKey = WalletIdempotencyKeys.withdrawal(rawKey);

        int attemptCount = 0;
        while(true){
            attemptCount++;
            try{
                return transactionExecutor.execute(
                        () -> withdrawOnce(command, rawKey, ledgerKey)
                );
            }catch (PessimisticLockingFailureException retryable){
                if(attemptCount >= MAX_TRANSACTION_ATTEMPTS){
                    throw retryable;
                }
            }
        }
    }

    private WithdrawalResult withdrawOnce(
            WithdrawalCommand command, String rawKey, String ledgerKey) {
        // wallet_id는 NOT NULL FK이므로 먼저 식별자만 조회한다.
        // 잔액 판단은 이 조회값이 아니라 아래 FOR UPDATE 스냅샷을 기준으로 한다.
        Long walletId = walletMapper.resolveWalletId(command.getUserId(), Money.KRW);
        if (walletId == null || walletId <= 0) {
            throw new InvalidWalletStateException("지갑을 찾을 수 없습니다.");
        }

        // Funding과 동일하게 지갑 → 계좌 순서로 잠가 교차 데드락을 예방한다.
        WalletBalanceSnapshot wallet =
                walletMapper.getWalletSnapshotForUpdateByWalletId(walletId);
        if (wallet == null) {
            throw new InvalidWalletStateException("지갑을 찾을 수 없습니다.");
        }
        WalletBalance balance = validateWalletSnapshot(
                wallet, command.getUserId(), walletId);
        Money amount = Money.krw(command.getAmount());

        // bankCode+accountNo로 비귀속 Mock 계좌를 식별한다(Client가 보낸 내부 ID는 없다).
        // 상태는 여기서 검사하지 않는다 - Replay가 현재 계좌 상태와 무관하게 재응답해야 하므로
        // (DEC-IDEMPOTENCY-STORAGE) 실제 검증은 claim 선점 이후 preflight에서 수행한다.
        Long linkedAccountId = bankTransferGateway.resolveAccountId(
                command.getBankCode(), command.getAccountNo());

        // 주문 INSERT의 계좌 FK가 S-lock을 잡기 전에 Adapter가 X-lock을 먼저 확보한다.
        // 같은 Mock 계좌를 쓰는 요청이 겹쳐도 잠금 승격 교착이 생기지 않는다.
        bankTransferGateway.lockAccount(linkedAccountId);

        // 지갑을 잡은 상태에서 요청을 선점해 계좌 FK 잠금이 순서를 뒤집지 않게 한다.
        WithdrawalOrderParam order = WithdrawalOrderParam.builder()
                .userId(command.getUserId())
                .walletId(walletId)
                .linkedAccountId(linkedAccountId)
                .amount(command.getAmount())
                .idempotencyKey(rawKey)
                .build();

        try{
            if(withdrawalMapper.insertWithdrawalRequest(order) != 1 || order.getId() == null || order.getId() <= 0){
                throw new WithdrawalIntegrityException("출금 요청을 선점하지 못했습니다.");
            }
        }catch (DuplicateKeyException duplicate){
            return replayClaimedRequest(command, rawKey, ledgerKey, linkedAccountId, duplicate);
        }catch (DataIntegrityViolationException invalidOrder){
            translateInvalidOrderReference(linkedAccountId, invalidOrder);
        }

        // 완료 요청의 replay는 당시 원장 스냅샷을 사용하므로 현재 상태 검증은 신규 claim에만 적용한다.
        WalletBalance balanceAfter;
        try {
            balanceAfter = balance.withdraw(amount);
        } catch (WalletBalance.InsufficientBalanceException insufficient) {
            throw new InsufficientAvailableBalanceException("지갑의 가용 잔액이 부족합니다.");
        }

        // 출금 방향은 PIN을 검사하지 않는다(DEC-WITHDRAWAL-DESTINATION).
        bankTransferGateway.preflight(BankAccountPreflightCommand.builder()
                .accountId(linkedAccountId)
                .build());

        // 계좌 입금
        BankTransferResult transfer = bankTransferGateway.deposit(BankTransferCommand.builder()
                .accountId(linkedAccountId)
                .amount(command.getAmount())
                .referenceType(REF_WITHDRAWAL_REQUEST)
                .referenceId(order.getId())
                .build());
        validateTransferResult(transfer, command.getAmount());

        if (withdrawalMapper.completeWithdrawalRequest(
                order.getId(), transfer.getBankTransactionId()) != 1) {
            throw new WithdrawalIntegrityException("출금 요청 완료 상태를 기록하지 못했습니다.");
        }

        if (walletMapper.updateWalletBalanceByWalletId(
                WalletBalanceUpdateParam.of(walletId, balance, balanceAfter)) != 1) {
            throw new WithdrawalIntegrityException("지갑 출금 잔액을 반영하지 못했습니다.");
        }

        // 출금: available만 감소, locked 불변, work_case_id는 NULL
        WalletTransactionParam transaction = WalletTransactionParam.builder()
                .walletId(walletId)
                .workCaseId(null)
                .transactionType(TX_WITHDRAWAL)
                .amount(command.getAmount())
                .availableBefore(balance.available())
                .availableAfter(balanceAfter.available())
                .lockedBefore(balance.locked())
                .lockedAfter(balanceAfter.locked())
                .referenceType(REF_WITHDRAWAL_REQUEST)
                .referenceId(order.getId())
                .idempotencyKey(ledgerKey)
                .build();

        if (walletMapper.insertWalletTransaction(transaction) != 1) {
            throw new WithdrawalIntegrityException("지갑 출금 원장을 기록하지 못했습니다.");
        }

        return WithdrawalResult.builder()
                .withdrawalRequestId(order.getId())
                .status(STATUS_COMPLETED)
                .bankTransactionId(transfer.getBankTransactionId())
                .replayed(false)
                .build();
    }

    private WithdrawalResult replayClaimedRequest(
            WithdrawalCommand command,
            String rawKey,
            String ledgerKey,
            Long linkedAccountId,
            DuplicateKeyException duplicate) {
        WithdrawalOrder existing = withdrawalMapper.findByIdempotencyKeyForShare(rawKey);

        if (existing == null) {
            throw new CannotAcquireLockException(
                    "멱등 요청 선점 결과를 확인할 수 없어 재시도가 필요합니다.",
                    duplicate
            );
        }

        validateSameRequest(existing, command, linkedAccountId);
        validateCompletedOrder(existing);

        WalletTransactionSnapshot snapshot =
                walletMapper.findTransactionByIdempotencyKey(ledgerKey);
        if (!isValidWithdrawalSnapshot(snapshot, existing)) {
            throw new WithdrawalIntegrityException("저장된 출금 원장 스냅샷이 요청과 일치하지 않습니다.");
        }

        return WithdrawalResult.builder()
                .withdrawalRequestId(existing.getId())
                .status(existing.getStatus())
                .bankTransactionId(existing.getMockBankTransactionId())
                .replayed(true)
                .build();

    }

    private void validateSameRequest(
            WithdrawalOrder existing, WithdrawalCommand command, Long linkedAccountId) {
        if (!Objects.equals(existing.getUserId(), command.getUserId())
                || !Objects.equals(existing.getLinkedAccountId(), linkedAccountId)
                || !Objects.equals(existing.getAmount(), command.getAmount())) {
            throw new IdempotencyKeyReusedException(
                    "같은 멱등 키로 다른 출금 요청이 접수되었습니다."
            );
        }
    }

    private void validateCompletedOrder(WithdrawalOrder order) {
        if (!STATUS_COMPLETED.equals(order.getStatus())
                || order.getMockBankTransactionId() == null
                || order.getMockBankTransactionId() <= 0) {
            throw new WithdrawalIntegrityException("완료되지 않은 출금 요청은 재응답할 수 없습니다.");
        }
    }

    private void validateTransferResult(BankTransferResult transfer, Long expectedAmount) {
        if (transfer == null
                || !TRANSFER_SUCCESS.equals(transfer.getStatus())
                || !expectedAmount.equals(transfer.getTransferredAmount())
                || transfer.getBankTransactionId() == null
                || transfer.getBankTransactionId() <= 0
                || transfer.getBankTranId() == null
                || transfer.getBankTranId().isBlank()
                || !isValidDepositBalance(transfer, expectedAmount)) {
            throw new BankTransferIntegrityException("은행 이체 결과가 요청과 일치하지 않습니다.");
        }
    }

    // 출금은 계좌 입금이므로 before + amount == after 여야 한다.
    private boolean isValidDepositBalance(
            BankTransferResult transfer, Long expectedAmount) {
        if (transfer.getBalanceBefore() == null
                || transfer.getBalanceAfter() == null
                || transfer.getBalanceBefore() < 0
                || transfer.getBalanceAfter() < 0) {
            return false;
        }
        try {
            return Math.addExact(
                    transfer.getBalanceBefore(), expectedAmount
            ) == transfer.getBalanceAfter();
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private boolean isValidWithdrawalSnapshot(
            WalletTransactionSnapshot snapshot, WithdrawalOrder order) {
        if (snapshot == null
                || snapshot.getId() == null
                || snapshot.getId() <= 0
                || !Objects.equals(snapshot.getWalletId(), order.getWalletId())
                || !Objects.equals(snapshot.getWalletUserId(), order.getUserId())
                || snapshot.getWorkCaseId() != null
                || !TX_WITHDRAWAL.equals(snapshot.getTransactionType())
                || !REF_WITHDRAWAL_REQUEST.equals(snapshot.getReferenceType())
                || !Objects.equals(snapshot.getReferenceId(), order.getId())
                || !order.getAmount().equals(snapshot.getAmount())) {
            return false;
        }

        try {
            WalletBalance before = WalletBalance.krw(
                    snapshot.getAvailableBefore(), snapshot.getLockedBefore());
            WalletBalance after = before.withdraw(Money.krw(order.getAmount()));
            return after.available() == snapshot.getAvailableAfter()
                    && after.locked() == snapshot.getLockedAfter();
        } catch (RuntimeException invalidSnapshot) {
            return false;
        }
    }

    private void translateInvalidOrderReference(
            Long linkedAccountId, DataIntegrityViolationException invalidOrder) {
        bankTransferGateway.preflight(BankAccountPreflightCommand.builder()
                .accountId(linkedAccountId)
                .build());
        throw new WithdrawalIntegrityException(
                "출금 요청의 참조 무결성을 확인할 수 없습니다.",
                invalidOrder
        );
    }

    private WalletBalance validateWalletSnapshot(
            WalletBalanceSnapshot wallet, Long expectedUserId, Long claimedWalletId) {
        if (wallet.getWalletId() == null
                || wallet.getWalletId() <= 0
                || !claimedWalletId.equals(wallet.getWalletId())
                || !expectedUserId.equals(wallet.getUserId())) {
            throw new WithdrawalIntegrityException("조회된 지갑 잔액 스냅샷이 올바르지 않습니다.");
        }
        try {
            return WalletBalance.krw(
                    wallet.getAvailableBalance(), wallet.getLockedBalance());
        } catch (RuntimeException invalidBalance) {
            throw new WithdrawalIntegrityException(
                    "조회된 지갑 잔액 스냅샷이 올바르지 않습니다.", invalidBalance);
        }
    }

    private void validateCommand(WithdrawalCommand command) {
        if (command == null
                || command.getUserId() == null
                || command.getUserId() <= 0
                || command.getBankCode() == null
                || command.getAccountNo() == null
                || command.getAmount() == null
                || command.getAmount() <= 0) {
            throw new InvalidWithdrawalRequestException(
                    "출금 요청의 사용자, 계좌, 금액을 확인해 주세요."
            );
        }
    }
}
