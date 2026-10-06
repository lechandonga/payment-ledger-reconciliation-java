package com.github.lechandonga.paymentledgerreconciliationjava.api;

import com.github.lechandonga.paymentledgerreconciliationjava.payment.Payment;
import com.github.lechandonga.paymentledgerreconciliationjava.payment.PaymentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    public record CreateRequest(String idempotencyKey, String merchantId, long amount) {
    }

    public record AuthorizeRequest(String idempotencyKey) {
    }

    public record AmountRequest(String idempotencyKey, long amount) {
    }

    public record PaymentResponse(String id, String merchantId, long amount, String status,
                                  long capturedAmount, long refundedAmount, long totalFee,
                                  long settledAmount) {
        static PaymentResponse of(Payment p) {
            return new PaymentResponse(p.getId(), p.getMerchantId(), p.getAmount(),
                    p.getStatus().name(), p.getCapturedAmount(), p.getRefundedAmount(),
                    p.getTotalFee(), p.getSettledAmount());
        }
    }

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping
    public PaymentResponse create(@RequestBody CreateRequest req) {
        return PaymentResponse.of(payments.create(req.idempotencyKey(), req.merchantId(), req.amount()));
    }

    @PostMapping("/{id}/authorize")
    public PaymentResponse authorize(@PathVariable String id, @RequestBody AuthorizeRequest req) {
        return PaymentResponse.of(payments.authorize(req.idempotencyKey(), id));
    }

    @PostMapping("/{id}/capture")
    public PaymentResponse capture(@PathVariable String id, @RequestBody AmountRequest req) {
        return PaymentResponse.of(payments.capture(req.idempotencyKey(), id, req.amount()));
    }

    @PostMapping("/{id}/refund")
    public PaymentResponse refund(@PathVariable String id, @RequestBody AmountRequest req) {
        return PaymentResponse.of(payments.refund(req.idempotencyKey(), id, req.amount()));
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable String id) {
        return PaymentResponse.of(payments.get(id));
    }
}
