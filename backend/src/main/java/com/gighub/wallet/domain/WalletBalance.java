package com.gighub.wallet.domain;

import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

/** Immutable wallet balances and their valid money transitions. */
public record WalletBalance(String currency, long available, long locked) {

    public WalletBalance {
        Objects.requireNonNull(currency, "currency must not be null");
        currency = currency.toUpperCase(Locale.ROOT);
        Currency.getInstance(currency);
        if (available < 0 || locked < 0) {
            throw new IllegalArgumentException("wallet balances must not be negative");
        }
    }

    public static WalletBalance krw(Long available, Long locked) {
        Objects.requireNonNull(available, "available balance must not be null");
        Objects.requireNonNull(locked, "locked balance must not be null");
        return new WalletBalance(Money.KRW, available, locked);
    }

    public WalletBalance credit(Money amount) {
        requireCurrency(amount);
        return new WalletBalance(currency, Math.addExact(available, amount.amount()), locked);
    }

    public WalletBalance withdraw(Money amount) {
        requireCurrency(amount);
        if (available < amount.amount()) {
            throw new InsufficientBalanceException("available balance is insufficient");
        }
        return new WalletBalance(currency, available - amount.amount(), locked);
    }

    public WalletBalance hold(Money amount) {
        requireCurrency(amount);
        if (available < amount.amount()) {
            throw new InsufficientBalanceException("available balance is insufficient");
        }
        return new WalletBalance(
                currency,
                available - amount.amount(),
                Math.addExact(locked, amount.amount()));
    }

    public WalletBalance release(Money amount) {
        requireCurrency(amount);
        if (locked < amount.amount()) {
            throw new InsufficientBalanceException("locked balance is insufficient");
        }
        return new WalletBalance(currency, available, locked - amount.amount());
    }

    public WalletBalance refund(Money amount) {
        WalletBalance released = release(amount);
        return new WalletBalance(
                currency,
                Math.addExact(released.available, amount.amount()),
                released.locked);
    }

    private void requireCurrency(Money amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        if (!currency.equals(amount.currency())) {
            throw new IllegalArgumentException("money currency must match wallet currency");
        }
    }

    public static final class InsufficientBalanceException extends IllegalStateException {
        public InsufficientBalanceException(String message) {
            super(message);
        }
    }
}
