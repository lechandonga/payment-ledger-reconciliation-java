package com.github.lechandonga.paymentledgerreconciliationjava.payment;

import com.github.lechandonga.paymentledgerreconciliationjava.api.IdempotencyService;
import com.github.lechandonga.paymentledgerreconciliationjava.api.RejectionException;
import com.github.lechandonga.paymentledgerreconciliationjava.ledger.LedgerService;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Payment acceptance: create, authorize, capture (partial allowed), refund (multiple allowed).
 * All state transitions are validated before any ledger posting, so rejected
 * operations leave no partial state. Methods are synchronized to keep the
 * in-memory store consistent under concurrency.
 */
@Service
public class PaymentService {

    /** Channel fee: 1% of captured amount, minimum 1 cent. */
    public static long feeFor(long captureAmount) {
        return Math.max(1, captureAmount / 100);
    }

    private final Map<String, Payment> payments = new ConcurrentHashMap<>();
    private final LedgerService ledger;
    private final IdempotencyService idempotency;

    public PaymentService(LedgerService ledger, IdempotencyService idempotency) {
        this.ledger = ledger;
        this.idempotency = idempotency;
    }

    public synchronized Payment create(String idempotencyKey, String merchantId, long amount) {
        return idempotency.execute(idempotencyKey, "create|" + merchantId + "|" + amount, () -> {
            requirePositive(amount);
            Payment payment = new Payment(merchantId, amount);
            payments.put(payment.getId(), payment);
            return payment;
        });
    }

    public synchronized Payment authorize(String idempotencyKey, String paymentId) {
        return idempotency.execute(idempotencyKey, "authorize|" + paymentId, () -> {
            Payment payment = get(paymentId);
            if (payment.getStatus() != PaymentStatus.CREATED) {
                throw new RejectionException(RejectionException.Reason.ILLEGAL_STATE_TRANSITION,
                        "authorize requires CREATED, got " + payment.getStatus());
            }
            payment.setStatus(PaymentStatus.AUTHORIZED);
            return payment;
        });
    }

    public synchronized Payment capture(String idempotencyKey, String paymentId, long amount) {
        return idempotency.execute(idempotencyKey, "capture|" + paymentId + "|" + amount, () -> {
            Payment payment = get(paymentId);
            requirePositive(amount);
            if (payment.getStatus() != PaymentStatus.AUTHORIZED
                    && payment.getStatus() != PaymentStatus.PARTIALLY_CAPTURED) {
                throw new RejectionException(RejectionException.Reason.ILLEGAL_STATE_TRANSITION,
                        "capture requires AUTHORIZED or PARTIALLY_CAPTURED, got " + payment.getStatus());
            }
            if (amount > payment.outstandingCapture()) {
                throw new RejectionException(RejectionException.Reason.CAPTURE_EXCEEDS_AUTHORIZED,
                        "capture " + amount + " exceeds outstanding authorized " + payment.outstandingCapture());
            }
            long fee = feeFor(amount);
            payment.addCapture(amount, fee);
            payment.setStatus(payment.outstandingCapture() == 0
                    ? PaymentStatus.CAPTURED : PaymentStatus.PARTIALLY_CAPTURED);
            ledger.postCapture(payment.getMerchantId(), payment.getId(), amount, fee);
            return payment;
        });
    }

    public synchronized Payment refund(String idempotencyKey, String paymentId, long amount) {
        return idempotency.execute(idempotencyKey, "refund|" + paymentId + "|" + amount, () -> {
            Payment payment = get(paymentId);
            requirePositive(amount);
            if (payment.getStatus() != PaymentStatus.CAPTURED
                    && payment.getStatus() != PaymentStatus.PARTIALLY_CAPTURED
                    && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
                throw new RejectionException(RejectionException.Reason.ILLEGAL_STATE_TRANSITION,
                        "refund requires a (partially) captured payment, got " + payment.getStatus());
            }
            if (amount > payment.refundableAmount()) {
                throw new RejectionException(RejectionException.Reason.REFUND_EXCEEDS_CAPTURED,
                        "refund " + amount + " exceeds refundable " + payment.refundableAmount());
            }
            payment.addRefund(amount);
            if (payment.refundableAmount() == 0) {
                payment.setStatus(PaymentStatus.REFUNDED);
            } else {
                payment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
            }
            ledger.postRefund(payment.getMerchantId(), payment.getId(), amount);
            return payment;
        });
    }

    public Payment get(String paymentId) {
        Payment payment = payments.get(paymentId);
        if (payment == null) {
            throw new RejectionException(RejectionException.Reason.PAYMENT_NOT_FOUND,
                    "no payment " + paymentId);
        }
        return payment;
    }

    public Collection<Payment> all() {
        return payments.values();
    }

    public void clear() {
        payments.clear();
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new RejectionException(RejectionException.Reason.INVALID_AMOUNT,
                    "amount must be positive, got " + amount);
        }
    }
}
