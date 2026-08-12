package com.gighub.wallet.exception;

public class EscrowIntegrityException extends RuntimeException {

    public EscrowIntegrityException(String message) {
        super(message);
    }

    public EscrowIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
