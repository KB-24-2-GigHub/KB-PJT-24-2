package com.gighub.wallet.service;

import com.gighub.wallet.service.command.SettlementWalletCommand;
import com.gighub.wallet.service.result.SettlementEscrowSnapshot;

/** Settlement outer Transaction에 참여하는 Wallet/Escrow/Ledger 공개 명령입니다. */
public interface SettlementWalletService {

    /** Settlement 다음 순서로 HELD Escrow를 잠그고 금액·소유 원장을 검증합니다. */
    SettlementEscrowSnapshot lockEscrow(SettlementWalletCommand command);

    /** 양측 KRW wallet ID를 해석하고 ID 오름차순으로 잠가 지급 전 잔액을 고정합니다. */
    SettlementWalletLock lockPayoutWallets(
            SettlementWalletCommand command, long escrowId);

    /** 잠금 Handle의 고용주 지갑과 최초 예치 원장이 같은지 검증합니다. */
    void verifyHeldEscrow(
            SettlementWalletCommand command,
            long escrowId,
            SettlementWalletLock walletLock);

    /** 지급 뒤 저장된 양측 원장과 RELEASED Escrow가 완전한지 대사합니다. */
    void verifyCompletedPayout(
            SettlementWalletCommand command, SettlementWalletLock walletLock);

    /** 이미 잠근 지갑 Snapshot을 expected-state로 갱신하고 지급과 원장을 원자 기록합니다. */
    SettlementAmounts release(
            SettlementWalletCommand command,
            long escrowId,
            SettlementWalletLock walletLock);

    /** Wallet 구현만 만들고 해석하는 잠금 증거입니다. 호출자는 내부 식별자나 잔액을 볼 수 없습니다. */
    interface SettlementWalletLock {
    }

    /**
     * 실제 자금 실행이 확정한 정산 금액입니다.
     *
     * <p>현재 #72 경로는 정상 근무 전액 지급만 담당합니다. 지각 분할 계산은 별도 기능 이슈가
     * 소유하므로 여기에서 추정하지 않습니다.</p>
     */
    record SettlementAmounts(
            long originalEscrowAmount,
            long workerPaidAmount,
            long ownerRefundAmount) {

        public static SettlementAmounts fullPayout(long amount) {
            return new SettlementAmounts(amount, amount, 0L);
        }
    }
}
