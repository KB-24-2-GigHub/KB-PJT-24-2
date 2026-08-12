package com.gighub.wallet.service.impl;

import com.gighub.bank.exception.BankTransferIntegrityException;
import com.gighub.bank.service.BankAccountPreflightCommand;
import com.gighub.bank.service.BankTransferCommand;
import com.gighub.bank.service.BankTransferGateway;
import com.gighub.bank.service.BankTransferResult;
import com.gighub.wallet.domain.Money;
import com.gighub.wallet.domain.WalletBalance;
import com.gighub.wallet.dto.FundingOrder;
import com.gighub.wallet.dto.WalletBalanceSnapshot;
import com.gighub.wallet.dto.WalletTransactionSnapshot;
import com.gighub.wallet.exception.FundingIntegrityException;
import com.gighub.wallet.exception.IdempotencyKeyReusedException;
import com.gighub.wallet.exception.InvalidFundingRequestException;
import com.gighub.wallet.exception.InvalidWalletStateException;
import com.gighub.wallet.idempotency.WalletIdempotencyKeys;
import com.gighub.wallet.mapper.FundingMapper;
import com.gighub.wallet.mapper.WalletMapper;
import com.gighub.wallet.mapper.param.FundingOrderParam;
import com.gighub.wallet.mapper.param.WalletBalanceUpdateParam;
import com.gighub.wallet.mapper.param.WalletTransactionParam;
import com.gighub.wallet.service.FundingService;
import com.gighub.wallet.service.command.FundingCommand;
import com.gighub.wallet.service.result.FundingResult;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class FundingServiceImpl implements FundingService {

    private static final int MAX_TRANSACTION_ATTEMPTS = 3;
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String TRANSFER_SUCCESS = "SUCCESS";
    private static final String TX_FUNDING = "FUNDING";
    private static final String REF_FUNDING_ORDER = "FUNDING_ORDER";

    private final FundingMapper fundingMapper;
    private final WalletMapper walletMapper;
    private final BankTransferGateway bankTransferGateway;
    private final FundingTransactionExecutor transactionExecutor;

    @Override
    public FundingResult fund(FundingCommand command) {
        validateCommand(command);
        String rawKey = WalletIdempotencyKeys.validateRawKey(command.getIdempotencyKey());
        String ledgerKey = WalletIdempotencyKeys.funding(rawKey);

        // 잠금 충돌 시 일부 단계만 반복하지 않고, rollback된 자금 명령 전체를 새 트랜잭션에서 재시도합니다.
        int attemptCount = 0;
        while (true) {
            attemptCount++;
            try {
                return transactionExecutor.execute(
                        () -> fundOnce(command, rawKey, ledgerKey)
                );
            } catch (PessimisticLockingFailureException retryable) {
                if (attemptCount >= MAX_TRANSACTION_ATTEMPTS) {
                    throw retryable;
                }
            }
        }
    }

    private FundingResult fundOnce(
            FundingCommand command, String rawKey, String ledgerKey) {
        Long walletId = walletMapper.resolveWalletId(command.getEmployerId(), Money.KRW);
        if (walletId == null || walletId <= 0) {
            throw new InvalidWalletStateException("지갑을 찾을 수 없습니다.");
        }

        // 지갑을 먼저 잠가 claim의 계좌 FK 잠금과 실제 계좌 잠금 순서를 일관되게 유지한다.
        WalletBalanceSnapshot wallet =
                walletMapper.getWalletSnapshotForUpdateByWalletId(walletId);
        if (wallet == null) {
            throw new InvalidWalletStateException("지갑을 찾을 수 없습니다.");
        }
        WalletBalance balance = validateWalletSnapshot(
                wallet, command.getEmployerId(), walletId);
        Money amount = Money.krw(command.getAmount());

        // bankCode+accountNo로 비귀속 Mock 계좌를 식별한다(Client가 보낸 내부 ID는 없다).
        // 상태·PIN은 여기서 검사하지 않는다 - Replay가 현재 계좌 상태와 무관하게 재응답해야 하므로
        // (DEC-IDEMPOTENCY-STORAGE) 실제 검증은 claim 선점 이후 preflight에서 수행한다.
        Long linkedAccountId = bankTransferGateway.resolveAccountId(
                command.getBankCode(), command.getAccountNo());

        // 주문 INSERT의 계좌 FK가 S-lock을 잡기 전에 Adapter가 X-lock을 먼저 확보한다.
        // 같은 Mock 계좌를 쓰는 요청이 겹쳐도 잠금 승격 교착이 생기지 않는다.
        bankTransferGateway.lockAccount(linkedAccountId);

        FundingOrderParam order = FundingOrderParam.builder()
                .employerId(command.getEmployerId())
                .linkedAccountId(linkedAccountId)
                .expectedAmount(command.getAmount())
                .idempotencyKey(rawKey)
                .build();

        try {
            if (fundingMapper.insertFundingOrder(order) != 1
                    || order.getId() == null || order.getId() <= 0) {
                throw new FundingIntegrityException("충전 주문을 선점하지 못했습니다.");
            }
        } catch (DuplicateKeyException duplicate) {
            return replayClaimedOrder(
                    command, rawKey, ledgerKey, walletId, linkedAccountId, duplicate);
        } catch (DataIntegrityViolationException invalidOrder) {
            translateInvalidOrderReference(linkedAccountId, command.getPin(), invalidOrder);
        }

        // claim을 선점한 뒤에야 상태·PIN을 검증한다.
        bankTransferGateway.preflight(BankAccountPreflightCommand.builder()
                .accountId(linkedAccountId)
                .pin(command.getPin())
                .build());

        WalletBalance balanceAfter;
        try {
            balanceAfter = balance.credit(amount);
        } catch (ArithmeticException overflow) {
            throw new FundingIntegrityException(
                    "지갑 충전 후 잔액이 허용 범위를 벗어났습니다.", overflow);
        }

        BankTransferResult transfer = bankTransferGateway.withdraw(BankTransferCommand.builder()
                .accountId(linkedAccountId)
                .pin(command.getPin())
                .amount(command.getAmount())
                .referenceType(REF_FUNDING_ORDER)
                .referenceId(order.getId())
                .build());
        validateTransferResult(transfer, command.getAmount());

        if (fundingMapper.completeFundingOrder(
                order.getId(), command.getAmount(), transfer.getBankTransactionId()) != 1) {
            throw new FundingIntegrityException("충전 주문 완료 상태를 기록하지 못했습니다.");
        }

        if (walletMapper.updateWalletBalanceByWalletId(
                WalletBalanceUpdateParam.of(walletId, balance, balanceAfter)) != 1) {
            throw new FundingIntegrityException("지갑 충전 잔액을 반영하지 못했습니다.");
        }

        WalletTransactionParam transaction = WalletTransactionParam.builder()
                .walletId(walletId)
                .workCaseId(null)
                .transactionType(TX_FUNDING)
                .amount(command.getAmount())
                .availableBefore(balance.available())
                .availableAfter(balanceAfter.available())
                .lockedBefore(balance.locked())
                .lockedAfter(balanceAfter.locked())
                .referenceType(REF_FUNDING_ORDER)
                .referenceId(order.getId())
                .idempotencyKey(ledgerKey)
                .build();
        if (walletMapper.insertWalletTransaction(transaction) != 1) {
            throw new FundingIntegrityException("지갑 충전 원장을 기록하지 못했습니다.");
        }

        return FundingResult.builder()
                .fundingOrderId(order.getId())
                .status(STATUS_COMPLETED)
                .bankTransactionId(transfer.getBankTransactionId())
                .availableBalance(balanceAfter.available())
                .lockedBalance(balanceAfter.locked())
                .replayed(false)
                .build();
    }

    private FundingResult replayClaimedOrder(
            FundingCommand command,
            String rawKey,
            String ledgerKey,
            Long walletId,
            Long linkedAccountId,
            DuplicateKeyException duplicate) {
        FundingOrder existing = fundingMapper.findByIdempotencyKeyForShare(rawKey);
        if (existing == null) {
            throw new CannotAcquireLockException(
                    "멱등 요청 선점 결과를 확인할 수 없어 재시도가 필요합니다.",
                    duplicate
            );
        }
        validateSameRequest(existing, command, linkedAccountId);
        validateCompletedOrder(existing);

        WalletTransactionSnapshot snapshot =
                walletMapper.findFundingTransactionSnapshot(
                        existing.getId(), walletId, ledgerKey);
        if (!isValidFundingSnapshot(snapshot, existing.getExpectedAmount())) {
            throw new FundingIntegrityException("저장된 충전 원장 스냅샷이 주문과 일치하지 않습니다.");
        }

        return FundingResult.builder()
                .fundingOrderId(existing.getId())
                .status(existing.getStatus())
                .bankTransactionId(existing.getMockBankTransactionId())
                .availableBalance(snapshot.getAvailableAfter())
                .lockedBalance(snapshot.getLockedAfter())
                .replayed(true)
                .build();
    }

    // PIN은 비교하지 않는다 - Fingerprint는 bankCode/accountNo(→linkedAccountId)와 amount만 포함한다
    // (DEC-IDEMPOTENCY-STORAGE).
    private void validateSameRequest(
            FundingOrder existing, FundingCommand command, Long linkedAccountId) {
        if (!Objects.equals(existing.getEmployerId(), command.getEmployerId())
                || !Objects.equals(existing.getLinkedAccountId(), linkedAccountId)
                || !Objects.equals(existing.getExpectedAmount(), command.getAmount())) {
            throw new IdempotencyKeyReusedException(
                    "같은 멱등 키로 다른 충전 요청이 접수되었습니다."
            );
        }
    }

    private void validateCompletedOrder(FundingOrder order) {
        if (!STATUS_COMPLETED.equals(order.getStatus())
                || order.getMockBankTransactionId() == null
                || order.getMockBankTransactionId() <= 0
                || !order.getExpectedAmount().equals(order.getTransferredAmount())) {
            throw new FundingIntegrityException("완료되지 않은 충전 주문은 재응답할 수 없습니다.");
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
                || !isValidWithdrawalBalance(transfer, expectedAmount)) {
            throw new BankTransferIntegrityException("은행 이체 결과가 요청과 일치하지 않습니다.");
        }
    }

    private boolean isValidWithdrawalBalance(
            BankTransferResult transfer, Long expectedAmount) {
        if (transfer.getBalanceBefore() == null
                || transfer.getBalanceAfter() == null
                || transfer.getBalanceBefore() < 0
                || transfer.getBalanceAfter() < 0) {
            return false;
        }
        try {
            return Math.subtractExact(
                    transfer.getBalanceBefore(), expectedAmount
            ) == transfer.getBalanceAfter();
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private boolean isValidFundingSnapshot(
            WalletTransactionSnapshot snapshot, Long expectedAmount) {
        if (snapshot == null
                || snapshot.getId() == null
                || snapshot.getId() <= 0
                || snapshot.getWalletId() == null
                || snapshot.getWalletId() <= 0
                || !expectedAmount.equals(snapshot.getAmount())) {
            return false;
        }
        try {
            WalletBalance before = WalletBalance.krw(
                    snapshot.getAvailableBefore(), snapshot.getLockedBefore());
            WalletBalance after = before.credit(Money.krw(expectedAmount));
            return after.available() == snapshot.getAvailableAfter()
                    && after.locked() == snapshot.getLockedAfter();
        } catch (RuntimeException invalidSnapshot) {
            return false;
        }
    }

    private void translateInvalidOrderReference(
            Long linkedAccountId, String pin, DataIntegrityViolationException invalidOrder) {
        bankTransferGateway.preflight(BankAccountPreflightCommand.builder()
                .accountId(linkedAccountId)
                .pin(pin)
                .build());
        throw new FundingIntegrityException(
                "충전 주문의 참조 무결성을 확인할 수 없습니다.",
                invalidOrder
        );
    }

    private WalletBalance validateWalletSnapshot(
            WalletBalanceSnapshot wallet, Long expectedUserId, Long expectedWalletId) {
        if (wallet.getWalletId() == null
                || wallet.getWalletId() <= 0
                || !expectedWalletId.equals(wallet.getWalletId())
                || !expectedUserId.equals(wallet.getUserId())) {
            throw new FundingIntegrityException("조회된 지갑 잔액 스냅샷이 올바르지 않습니다.");
        }
        try {
            return WalletBalance.krw(
                    wallet.getAvailableBalance(), wallet.getLockedBalance());
        } catch (RuntimeException invalidBalance) {
            throw new FundingIntegrityException(
                    "조회된 지갑 잔액 스냅샷이 올바르지 않습니다.", invalidBalance);
        }
    }

    private void validateCommand(FundingCommand command) {
        if (command == null
                || command.getEmployerId() == null
                || command.getEmployerId() <= 0
                || command.getBankCode() == null
                || command.getAccountNo() == null
                || command.getPin() == null
                || command.getAmount() == null
                || command.getAmount() <= 0) {
            throw new InvalidFundingRequestException(
                    "충전 요청의 사용자, 계좌, PIN, 금액을 확인해 주세요."
            );
        }
    }
}
