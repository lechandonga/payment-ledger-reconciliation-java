package com.github.lechandonga.paymentledgerreconciliationjava.web;

import com.github.lechandonga.paymentledgerreconciliationjava.domain.Payment;
import com.github.lechandonga.paymentledgerreconciliationjava.domain.RefundOrder;
import com.github.lechandonga.paymentledgerreconciliationjava.dto.Requests;
import com.github.lechandonga.paymentledgerreconciliationjava.dto.Views;
import com.github.lechandonga.paymentledgerreconciliationjava.repo.PaymentRepository;
import com.github.lechandonga.paymentledgerreconciliationjava.service.IdempotencyService;
import com.github.lechandonga.paymentledgerreconciliationjava.service.PaymentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentRepository paymentRepository;
    private final IdempotencyService idempotency;

    public PaymentController(PaymentService paymentService, PaymentRepository paymentRepository,
                             IdempotencyService idempotency) {
        this.paymentService = paymentService;
        this.paymentRepository = paymentRepository;
        this.idempotency = idempotency;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Requests.CreatePaymentRequest req) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("payment", idempotency.execute(req.idemKey(),
                idempotency.hash("CREATE", req.merchantId(), req.channel(), req.amount(),
                        req.currency(), req.feeRateBp()),
                java.util.Map.class, () -> Views.payment(paymentService.createPayment(req))));
        return result;
    }

    @PostMapping("/{paymentNo}/authorize")
    public Map<String, Object> authorize(@PathVariable String paymentNo) {
        Payment p = paymentService.authorize(paymentNo);
        return Map.of("accepted", true, "payment", Views.payment(p));
    }

    @PostMapping("/{paymentNo}/captures")
    public Map<String, Object> capture(@PathVariable String paymentNo,
                                       @RequestBody Requests.CaptureRequest req) {
        Object payment = idempotency.execute(req.idemKey(),
                idempotency.hash("CAPTURE", paymentNo, req.amount()),
                java.util.Map.class,
                () -> Views.payment(paymentService.capture(paymentNo,
                        req.amount() == null ? 0 : req.amount())));
        return Map.of("accepted", true, "payment", payment);
    }

    @PostMapping("/{paymentNo}/refunds")
    public Map<String, Object> refund(@PathVariable String paymentNo,
                                      @RequestBody Requests.RefundRequest req) {
        Object refund = idempotency.execute(req.idemKey(),
                idempotency.hash("REFUND", paymentNo, req.amount()),
                java.util.Map.class,
                () -> Views.refund(paymentService.refund(paymentNo, req.idemKey(),
                        req.amount() == null ? 0 : req.amount())));
        return Map.of("accepted", true, "refund", refund);
    }

    @GetMapping("/{paymentNo}")
    public Map<String, Object> get(@PathVariable String paymentNo) {
        Payment p = paymentRepository.findByNo(paymentNo);
        if (p == null) {
            throw new com.github.lechandonga.paymentledgerreconciliationjava.domain.BusinessException(
                    com.github.lechandonga.paymentledgerreconciliationjava.domain.ErrorCode.PAYMENT_NOT_FOUND, paymentNo);
        }
        return Map.of("payment", Views.payment(p));
    }
}
