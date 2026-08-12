package com.gighub.wallet.dto;

import lombok.Builder;
import lombok.Value;

/**
 * 내 지갑 요약 응답입니다.
 *
 * <p>{@code availableBalance}만 대표 잔액이며 {@code lockedBalance}와 합산한 값을 따로
 * 제공하지 않습니다. 합산 표시가 필요하면 클라이언트가 계산합니다.</p>
 */
@Value
@Builder
public class WalletBalanceResponse {

    String currency;
    Long availableBalance;
    Long lockedBalance;

    public static WalletBalanceResponse of(
            String currency,
            Long availableBalance,
            Long lockedBalance) {
        return WalletBalanceResponse.builder()
                .currency(currency)
                .availableBalance(availableBalance)
                .lockedBalance(lockedBalance)
                .build();
    }
}
