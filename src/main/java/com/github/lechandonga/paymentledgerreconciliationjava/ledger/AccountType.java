package com.github.lechandonga.paymentledgerreconciliationjava.ledger;

public enum AccountType {
    /** Money in transit from the card channel. Debit-normal. */
    CHANNEL_CLEARING,
    /** Net amount owed to a merchant. Credit-normal, must never go negative. */
    MERCHANT_PAYABLE,
    /** Cash already paid out to a merchant at settlement. Debit-normal. */
    MERCHANT_CASH,
    /** Channel fee income. Credit-normal. */
    FEE_REVENUE
}
