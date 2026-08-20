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
    /** 최초 약정·예치액입니다. */
    private final long amount;
    private final long workerPaidAmount;
    private final long ownerRefundAmount;
    private final String employerLedgerKey;
    private final String workerLedgerKey;
    private final String employerRefundLedgerKey;
}
