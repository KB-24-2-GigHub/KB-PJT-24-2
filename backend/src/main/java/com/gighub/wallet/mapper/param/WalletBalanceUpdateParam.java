package com.gighub.wallet.mapper.param;

import com.gighub.wallet.domain.WalletBalance;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WalletBalanceUpdateParam {
    private final Long walletId;
    private final String currency;
    private final Long availableBefore;
    private final Long availableAfter;
    private final Long lockedBefore;
    private final Long lockedAfter;

    public static WalletBalanceUpdateParam of(
            Long walletId, WalletBalance before, WalletBalance after) {
        return WalletBalanceUpdateParam.builder()
                .walletId(walletId)
                .currency(before.currency())
                .availableBefore(before.available())
                .availableAfter(after.available())
                .lockedBefore(before.locked())
                .lockedAfter(after.locked())
                .build();
    }
}
