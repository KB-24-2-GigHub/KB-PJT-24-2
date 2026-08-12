package com.gighub.wallet.service;

import com.gighub.wallet.service.command.SettlementWalletCommand;

/** Settlement outer Transaction에 참여하는 Wallet/Escrow/Ledger 공개 명령입니다. */
public interface SettlementWalletService {

    /** Settlement 다음 순서로 HELD Escrow를 잠그고 금액·소유 원장을 검증합니다. */
    long lockHeldEscrow(SettlementWalletCommand command);

    /** 저장된 양측 원장과 RELEASED Escrow가 완전하면 replay임을 반환합니다. */
    boolean verifyReplay(SettlementWalletCommand command);

    /** user ID 오름차순으로 지갑을 잠근 뒤 Escrow 지급과 양측 원장을 원자적으로 기록합니다. */
    SettlementAmounts release(SettlementWalletCommand command, long escrowId);

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
