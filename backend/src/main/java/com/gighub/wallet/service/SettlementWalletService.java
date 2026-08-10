package com.gighub.wallet.service;

import com.gighub.wallet.service.command.SettlementWalletCommand;

/** Settlement outer Transaction에 참여하는 Wallet/Escrow/Ledger 공개 명령입니다. */
public interface SettlementWalletService {

    /** 저장된 양측 원장과 RELEASED Escrow가 완전하면 replay임을 반환합니다. */
    boolean verifyReplay(SettlementWalletCommand command);

    /** 현행 user ID 오름차순 지갑 조회 잠금 뒤 Escrow 지급과 양측 원장을 원자적으로 기록합니다. */
    void release(SettlementWalletCommand command);
}
