package com.gighub.wallet.service.impl;

import com.gighub.common.exception.ConflictException;
import com.gighub.wallet.dto.WalletBalanceSnapshot;
import com.gighub.wallet.mapper.WalletMapper;
import com.gighub.wallet.mapper.param.WalletTransactionParam;
import com.gighub.wallet.service.AcceptEscrowHold;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;

/** Wallet 소유 SQL을 사용해 수락 예치를 원자적으로 수행합니다. */
@Service
public class AcceptEscrowHoldImpl implements AcceptEscrowHold {

    private static final String TRANSACTION_TYPE = "ESCROW_HOLD";
    private static final String REFERENCE_TYPE = "ESCROW";
    private static final String LEDGER_KEY_PREFIX = "EHLD:";
    private static final String OPERATION_LABEL = "INVITATION_ACCEPT\n";
    private static final String INSUFFICIENT_BALANCE =
            "사장님의 예치 가능 잔액이 부족하여 근무를 확정할 수 없습니다.";

    private final WalletMapper walletMapper;

    public AcceptEscrowHoldImpl(WalletMapper walletMapper) {
        this.walletMapper = walletMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long hold(
            long employerId,
            long workCaseId,
            long amount,
            long claimId,
            LocalDateTime acceptedAt) {
        WalletBalanceSnapshot wallet = walletMapper.getWalletSnapshotForUpdate(employerId);
        if (wallet == null) {
            throw new IllegalStateException("OWNER 지갑을 찾을 수 없습니다.");
        }
        if (wallet.getAvailableBalance() < amount) {
            throw new ConflictException(INSUFFICIENT_BALANCE);
        }

        long availableAfter = Math.subtractExact(wallet.getAvailableBalance(), amount);
        long lockedAfter = Math.addExact(wallet.getLockedBalance(), amount);
        if (walletMapper.lockEmployerFunds(employerId, amount) != 1) {
            throw new IllegalStateException("예치 반영 결과가 예상과 다릅니다.");
        }
        if (walletMapper.insertHeldEscrowAt(workCaseId, amount, acceptedAt) != 1) {
            throw new IllegalStateException("에스크로를 생성하지 못했습니다.");
        }

        long escrowId = Objects.requireNonNull(
                walletMapper.getEscrowIdByWorkCaseId(workCaseId), "생성된 에스크로 식별자");
        int recorded = walletMapper.insertWalletTransaction(WalletTransactionParam.builder()
                .walletId(wallet.getWalletId())
                .workCaseId(workCaseId)
                .transactionType(TRANSACTION_TYPE)
                .amount(amount)
                .availableBefore(wallet.getAvailableBalance())
                .availableAfter(availableAfter)
                .lockedBefore(wallet.getLockedBalance())
                .lockedAfter(lockedAfter)
                .referenceType(REFERENCE_TYPE)
                .referenceId(escrowId)
                .idempotencyKey(ledgerKey(claimId))
                .build());
        if (recorded != 1) {
            throw new IllegalStateException("에스크로 예치 원장을 기록하지 못했습니다.");
        }
        return escrowId;
    }

    private static String ledgerKey(long claimId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((OPERATION_LABEL + claimId).getBytes(StandardCharsets.UTF_8));
            return LEDGER_KEY_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
