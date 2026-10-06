package com.github.lechandonga.paymentledgerreconciliationjava.domain;

import java.time.LocalDateTime;

public class RefundOrder {
    public long id;
    public String refundNo;
    public String paymentNo;
    public String idemKey;
    public long amount;
    public long feeReversed;
    public String status;
    public LocalDateTime createdAt;
}
