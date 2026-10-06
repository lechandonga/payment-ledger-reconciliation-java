package com.github.lechandonga.paymentledgerreconciliationjava.domain;

import java.time.LocalDateTime;

public class Payment {
    public long id;
    public String paymentNo;
    public String idemKey;
    public String merchantId;
    public String channel;
    public String status;
    public long amount;
    public String currency;
    public int feeRateBp;
    public long capturedAmount;
    public long refundedAmount;
    public long feeTotal;
    public long feeRefunded;
    public long settledAmount;
    public long feeSettled;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;

    public long netCaptured() {
        return capturedAmount - refundedAmount;
    }

    public long netFee() {
        return feeTotal - feeRefunded;
    }

    public long unsettledGross() {
        return netCaptured() - settledAmount;
    }

    public long unsettledFee() {
        return netFee() - feeSettled;
    }

    /** 根据金额事实推导状态，避免出现非法中间态。 */
    public String deriveStatus() {
        if (capturedAmount == 0) {
            return "AUTHORIZED";
        }
        if (refundedAmount >= capturedAmount) {
            return "REFUNDED";
        }
        if (refundedAmount > 0) {
            return "PARTIALLY_REFUNDED";
        }
        if (capturedAmount >= amount) {
            return "CAPTURED";
        }
        return "PARTIALLY_CAPTURED";
    }
}
