package com.github.lechandonga.paymentledgerreconciliationjava.dto;

import com.github.lechandonga.paymentledgerreconciliationjava.domain.Payment;
import com.github.lechandonga.paymentledgerreconciliationjava.domain.RefundOrder;

import java.util.LinkedHashMap;
import java.util.Map;

public class Views {

    public static Map<String, Object> payment(Payment p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paymentNo", p.paymentNo);
        m.put("merchantId", p.merchantId);
        m.put("channel", p.channel);
        m.put("status", p.status);
        m.put("amount", p.amount);
        m.put("currency", p.currency);
        m.put("feeRateBp", p.feeRateBp);
        m.put("capturedAmount", p.capturedAmount);
        m.put("refundedAmount", p.refundedAmount);
        m.put("refundableAmount", p.capturedAmount - p.refundedAmount);
        m.put("capturableAmount", p.amount - p.capturedAmount);
        m.put("feeTotal", p.feeTotal);
        m.put("feeRefunded", p.feeRefunded);
        m.put("settledAmount", p.settledAmount);
        m.put("feeSettled", p.feeSettled);
        m.put("merchantPayable", p.netCaptured() - p.netFee());
        return m;
    }

    public static Map<String, Object> refund(RefundOrder r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("refundNo", r.refundNo);
        m.put("paymentNo", r.paymentNo);
        m.put("idemKey", r.idemKey);
        m.put("amount", r.amount);
        m.put("feeReversed", r.feeReversed);
        m.put("status", r.status);
        return m;
    }
}
