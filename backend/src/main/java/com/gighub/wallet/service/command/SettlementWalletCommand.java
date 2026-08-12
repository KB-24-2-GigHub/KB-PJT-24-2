package com.gighub.wallet.service.command;

import lombok.Builder;
import lombok.Getter;

/** Settlement가 Wallet participant에 전달하는 persistence-free 자금 명령입니다. */
@Getter
@Builder
public class SettlementWalletCommand {

    private final long workCaseId;
    private final long employerId;
    private final long workerId;
    private final long amount;
    private final String employerLedgerKey;
    private final String workerLedgerKey;
}
