package com.github.lechandonga.paymentledgerreconciliationjava.payment;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A payment order. All monetary values are minor units (cents).
 */
public class Payment {

    public record Capture(long amount, long fee) {
    }

    public record Refund(long amount) {
    }

    private final String id = UUID.randomUUID().toString();
    private final String merchantId;
    private final long amount;
    private PaymentStatus status = PaymentStatus.CREATED;
    private long capturedAmount;
    private long refundedAmount;
    private long totalFee;
    private long settledAmount;
    private final List<Capture> captures = new ArrayList<>();
    private final List<Refund> refunds = new ArrayList<>();

    public Payment(String merchantId, long amount) {
        this.merchantId = merchantId;
        this.amount = amount;
    }

    public String getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public long getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentStatus status) {
        this.status = status;
    }

    public long getCapturedAmount() {
        return capturedAmount;
    }

    public long getRefundedAmount() {
        return refundedAmount;
    }

    public long getTotalFee() {
        return totalFee;
    }

    public long getSettledAmount() {
        return settledAmount;
    }

    public List<Capture> getCaptures() {
        return captures;
    }

    public List<Refund> getRefunds() {
        return refunds;
    }

    public long outstandingCapture() {
        return amount - capturedAmount;
    }

    public long refundableAmount() {
        return capturedAmount - refundedAmount;
    }

    /** Net merchant payable still owed for this payment (captures minus fee minus refunds minus settled). */
    public long unsettledNet() {
        return capturedAmount - totalFee - refundedAmount - settledAmount;
    }

    void addCapture(long amount, long fee) {
        this.capturedAmount += amount;
        this.totalFee += fee;
        this.captures.add(new Capture(amount, fee));
    }

    void addRefund(long amount) {
        this.refundedAmount += amount;
        this.refunds.add(new Refund(amount));
    }

    public void addSettled(long amount) {
        this.settledAmount += amount;
    }
}
