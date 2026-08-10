package com.gighub.wallet.domain;

import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

/** A positive amount in one ISO-4217 currency. */
public record Money(String currency, long amount) {
    public static final String KRW = "KRW";

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
        currency = currency.toUpperCase(Locale.ROOT);
        Currency.getInstance(currency);
        if (amount <= 0) {
            throw new IllegalArgumentException("money amount must be positive");
        }
    }

    public static Money krw(Long amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        return new Money(KRW, amount);
    }
}
