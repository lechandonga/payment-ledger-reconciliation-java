package com.github.lechandonga.paymentledgerreconciliationjava.tests;

import com.github.lechandonga.paymentledgerreconciliationjava.dto.Requests;

public final class TestSupport {

    private TestSupport() {
    }

    public static Requests.CreatePaymentRequest create(String idemKey, String merchant, long amount, int feeRateBp) {
        return new Requests.CreatePaymentRequest(idemKey, merchant, "MOCK", amount, "CNY", feeRateBp);
    }

    public static Requests.ChannelEntry entry(String channelNo, String paymentNo, Long amount, String status) {
        return new Requests.ChannelEntry(channelNo, paymentNo, amount, status);
    }
}
