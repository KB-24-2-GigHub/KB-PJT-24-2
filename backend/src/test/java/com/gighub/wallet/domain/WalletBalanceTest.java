package com.gighub.wallet.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WalletBalanceTest {

    @Test
    void moneyMustBePositiveAndUseAValidCurrency() {
        assertThrows(IllegalArgumentException.class, () -> Money.krw(0L));
        assertThrows(IllegalArgumentException.class, () -> new Money("INVALID", 1L));
    }

    @Test
    void walletRejectsNegativeBalancesAndDifferentCurrencies() {
        assertThrows(IllegalArgumentException.class, () -> WalletBalance.krw(-1L, 0L));

        WalletBalance balance = WalletBalance.krw(10L, 0L);
        assertThrows(
                IllegalArgumentException.class,
                () -> balance.credit(new Money("USD", 1L)));
    }

    @Test
    void creditAndWithdrawalPreserveLockedBalance() {
        WalletBalance initial = WalletBalance.krw(100L, 20L);

        WalletBalance credited = initial.credit(Money.krw(30L));
        WalletBalance withdrawn = credited.withdraw(Money.krw(50L));

        assertEquals(130L, credited.available());
        assertEquals(20L, credited.locked());
        assertEquals(80L, withdrawn.available());
        assertEquals(20L, withdrawn.locked());
    }

    @Test
    void holdMovesAvailableToLockedAndRefundReversesIt() {
        WalletBalance initial = WalletBalance.krw(100L, 20L);

        WalletBalance held = initial.hold(Money.krw(30L));
        WalletBalance refunded = held.refund(Money.krw(30L));

        assertEquals(70L, held.available());
        assertEquals(50L, held.locked());
        assertEquals(initial, refunded);
    }

    @Test
    void releaseConsumesOnlyLockedBalance() {
        WalletBalance released = WalletBalance.krw(100L, 50L).release(Money.krw(30L));

        assertEquals(100L, released.available());
        assertEquals(20L, released.locked());
    }

    @Test
    void insufficientAndOverflowingTransitionsFailClosed() {
        assertThrows(
                WalletBalance.InsufficientBalanceException.class,
                () -> WalletBalance.krw(10L, 0L).withdraw(Money.krw(11L)));
        assertThrows(
                WalletBalance.InsufficientBalanceException.class,
                () -> WalletBalance.krw(10L, 5L).release(Money.krw(6L)));
        assertThrows(
                ArithmeticException.class,
                () -> WalletBalance.krw(Long.MAX_VALUE, 0L).credit(Money.krw(1L)));
    }
}
