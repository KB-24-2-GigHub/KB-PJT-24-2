package com.gighub.wallet.service.command;

import lombok.Builder;
import lombok.Getter;

/** NO_SHOW 환불에 필요한 OWNER Wallet 명령만 전달합니다. */
@Getter
@Builder
public class NoShowRefundWalletCommand {
    private final long workCaseId;
    private final long employerId;
    private final long amount;
    private final String employerLedgerKey;
}
