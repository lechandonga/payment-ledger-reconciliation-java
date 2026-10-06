package com.github.lechandonga.paymentledgerreconciliationjava.api;

/**
 * Business rejection with a machine-readable reason code.
 * Rejected operations never leave partial state behind.
 */
public class RejectionException extends RuntimeException {

    public enum Reason {
        PAYMENT_NOT_FOUND,
        ILLEGAL_STATE_TRANSITION,
        CAPTURE_EXCEEDS_AUTHORIZED,
        REFUND_EXCEEDS_CAPTURED,
        INVALID_AMOUNT,
        IDEMPOTENCY_KEY_CONFLICT,
        BATCH_ALREADY_COMPLETED,
        BATCH_IN_PROGRESS,
        SETTLEMENT_BALANCE_NEGATIVE
    }

    private final Reason reason;

    public RejectionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
