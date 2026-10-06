package com.github.lechandonga.paymentledgerreconciliationjava.settlement;

public enum DiffType {
    /** Present on both sides but amounts differ. */
    AMOUNT_MISMATCH,
    /** Present in our ledger only. */
    MISSING_IN_CHANNEL,
    /** Present in the channel file only. */
    MISSING_IN_INTERNAL,
    /** Present on both sides but lifecycle status differs. */
    STATUS_MISMATCH
}
