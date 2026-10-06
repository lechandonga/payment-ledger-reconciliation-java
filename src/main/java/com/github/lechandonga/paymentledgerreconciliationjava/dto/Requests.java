package com.github.lechandonga.paymentledgerreconciliationjava.dto;

public class Requests {
    public record CreatePaymentRequest(String idemKey, String merchantId, String channel,
                                       Long amount, String currency, Integer feeRateBp) {
    }

    public record CaptureRequest(String idemKey, Long amount) {
    }

    public record RefundRequest(String idemKey, Long amount) {
    }

    public record ChannelEntry(String channelPaymentNo, String paymentNo, Long amount, String status) {
    }

    public record ReconRequest(String batchNo, java.util.List<String> merchantIds,
                               java.util.List<ChannelEntry> channelEntries) {
    }
}
