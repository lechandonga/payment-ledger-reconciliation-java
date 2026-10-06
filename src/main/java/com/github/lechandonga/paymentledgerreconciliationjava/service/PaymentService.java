package com.github.lechandonga.paymentledgerreconciliationjava.service;

import com.github.lechandonga.paymentledgerreconciliationjava.domain.BusinessException;
import com.github.lechandonga.paymentledgerreconciliationjava.domain.ErrorCode;
import com.github.lechandonga.paymentledgerreconciliationjava.domain.Payment;
import com.github.lechandonga.paymentledgerreconciliationjava.domain.RefundOrder;
import com.github.lechandonga.paymentledgerreconciliationjava.dto.Requests;
import com.github.lechandonga.paymentledgerreconciliationjava.repo.IdempotencyRepository;
import com.github.lechandonga.paymentledgerreconciliationjava.repo.LedgerRepository;
import com.github.lechandonga.paymentledgerreconciliationjava.repo.PaymentRepository;
import com.github.lechandonga.paymentledgerreconciliationjava.repo.RefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final LedgerRepository ledger;
    private final LedgerService ledgerService;
    private final IdempotencyRepository idempotency;

    public PaymentService(PaymentRepository payments, RefundRepository refunds,
                          LedgerRepository ledger, LedgerService ledgerService,
                          IdempotencyRepository idempotency) {
        this.payments = payments;
        this.refunds = refunds;
        this.ledger = ledger;
        this.ledgerService = ledgerService;
        this.idempotency = idempotency;
    }

    @Transactional
    public Payment createPayment(Requests.CreatePaymentRequest req) {
        long amount = req.amount() == null ? 0 : req.amount();
        int feeRateBp = req.feeRateBp() == null ? 0 : req.feeRateBp();
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT, "支付金额必须大于 0");
        }
        if (feeRateBp < 0 || feeRateBp > 10000) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "feeRateBp 必须在 0~10000 之间");
        }
        if (req.merchantId() == null || req.merchantId().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "merchantId 不能为空");
        }
        Payment p = new Payment();
        p.paymentNo = "PAY" + UUID.randomUUID().toString().replace("-", "");
        p.idemKey = req.idemKey();
        p.merchantId = req.merchantId();
        p.channel = req.channel() == null ? "MOCK_CHANNEL" : req.channel();
        p.amount = amount;
        p.currency = req.currency() == null ? "CNY" : req.currency();
        p.feeRateBp = feeRateBp;
        p.status = "CREATED";
        ledger.ensureGlobalAccounts();
        ledger.ensureMerchantAccounts(p.merchantId);
        payments.insert(p);
        log.info("[PAYMENT] 创建支付单 paymentNo={} merchant={} amount={} feeRateBp={}",
                p.paymentNo, p.merchantId, p.amount, p.feeRateBp);
        return payments.findByNo(p.paymentNo);
    }

    /** 授权不涉及真实资金变动，只做状态流转：CREATED -> AUTHORIZED。 */
    @Transactional
    public Payment authorize(String paymentNo) {
        Payment p = payments.lockByNo(paymentNo);
        if (p == null) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND, paymentNo);
        }
        if (!"CREATED".equals(p.status)) {
            throw new BusinessException(ErrorCode.ILLEGAL_TRANSITION,
                    "支付单 " + paymentNo + " 当前状态 " + p.status + "，只有 CREATED 可以授权");
        }
        payments.applyCapture(p, 0, 0, "AUTHORIZED");
        log.info("[PAYMENT] 授权成功 paymentNo={}", paymentNo);
        return payments.findByNo(paymentNo);
    }

    @Transactional
    public Payment capture(String paymentNo, long amount) {
        Payment p = payments.lockByNo(paymentNo);
        if (p == null) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND, paymentNo);
        }
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT, "请款金额必须大于 0");
        }
        boolean canCapture = "AUTHORIZED".equals(p.status) || "PARTIALLY_CAPTURED".equals(p.status);
        if (!canCapture) {
            throw new BusinessException(ErrorCode.ILLEGAL_TRANSITION,
                    "支付单 " + paymentNo + " 当前状态 " + p.status + "，不允许请款");
        }
        long remaining = p.amount - p.capturedAmount;
        if (amount > remaining) {
            throw new BusinessException(ErrorCode.CAPTURE_EXCEEDS_AUTH,
                    "本次请款 " + amount + " 超过剩余可请款额度 " + remaining);
        }
        long newCaptured = p.capturedAmount + amount;
        long fee = amount * p.feeRateBp / 10000;
        long newFee = p.feeTotal + fee;
        String status = newCaptured >= p.amount ? "CAPTURED" : "PARTIALLY_CAPTURED";

        // 先记账、后改状态，同一事务，任何异常整体回滚
        ledgerService.post(p.paymentNo, null, null, "CAPTURE",
                ledgerService.captureLines(p.merchantId, amount, fee));
        payments.applyCapture(p, newCaptured, newFee, status);
        log.info("[PAYMENT] 请款成功 paymentNo={} capture={} fee={} status={}", paymentNo, amount, fee, status);
        return payments.findByNo(paymentNo);
    }

    @Transactional
    public RefundOrder refund(String paymentNo, String idemKey, long amount) {
        Payment p = payments.lockByNo(paymentNo);
        if (p == null) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND, paymentNo);
        }
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT, "退款金额必须大于 0");
        }
        boolean canRefund = "CAPTURED".equals(p.status) || "PARTIALLY_CAPTURED".equals(p.status)
                || "PARTIALLY_REFUNDED".equals(p.status);
        if (!canRefund) {
            throw new BusinessException(ErrorCode.ILLEGAL_TRANSITION,
                    "支付单 " + paymentNo + " 当前状态 " + p.status + "，没有可退款的请款资金");
        }
        long remaining = p.capturedAmount - p.refundedAmount;
        if (amount > remaining) {
            throw new BusinessException(ErrorCode.REFUND_EXCEEDS_CAPTURED,
                    "本次退款 " + amount + " 超过剩余可退款额度 " + remaining);
        }
        // 手续费按退款占比等比冲回，整数除法向下取整；首次退款承担舍入差，保证累计不超过已收手续费
        long feeReversed;
        long newRefunded = p.refundedAmount + amount;
        if (newRefunded >= p.capturedAmount) {
            feeReversed = p.feeTotal - p.feeRefunded;
        } else {
            feeReversed = amount * p.feeRateBp / 10000;
            if (p.feeRefunded + feeReversed > p.feeTotal) {
                feeReversed = p.feeTotal - p.feeRefunded;
            }
        }
        long net = amount - feeReversed;
        // 商户待结算（未结算部分）必须足额，不允许为负
        long payableAvailable = (p.netCaptured() - p.settledAmount) - (p.netFee() - p.feeSettled);
        if (net > payableAvailable) {
            throw new BusinessException(ErrorCode.MERCHANT_BALANCE_INSUFFICIENT,
                    "本次退款净额 " + net + " 超过商户未结算待结算余额 " + payableAvailable
                            + "（已结算资金不能直接退款）");
        }

        RefundOrder r = new RefundOrder();
        r.refundNo = "REF" + UUID.randomUUID().toString().replace("-", "");
        r.paymentNo = paymentNo;
        r.idemKey = idemKey;
        r.amount = amount;
        r.feeReversed = feeReversed;
        r.status = "SUCCESS";

        ledgerService.post(p.paymentNo, r.refundNo, null, "REFUND",
                ledgerService.refundLines(p.merchantId, amount, feeReversed));
        String newStatus = newRefunded >= p.capturedAmount ? "REFUNDED" : "PARTIALLY_REFUNDED";
        payments.applyRefund(p, newRefunded, p.feeRefunded + feeReversed, newStatus);
        refunds.insert(r);
        log.info("[PAYMENT] 退款成功 paymentNo={} refundNo={} amount={} feeReversed={} status={}",
                paymentNo, r.refundNo, amount, feeReversed, newStatus);
        return r;
    }
}
