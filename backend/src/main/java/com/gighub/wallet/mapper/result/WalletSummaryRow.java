package com.gighub.wallet.mapper.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** 지갑 잔액 조회 열을 persistence 경계 안에서 전달합니다. */
@Getter
@Builder
@AllArgsConstructor
public final class WalletSummaryRow {

    private final Long walletId;
    private final Long availableBalance;
    private final Long lockedBalance;
}
