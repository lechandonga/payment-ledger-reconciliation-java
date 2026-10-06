package com.github.lechandonga.paymentledgerreconciliationjava.settlement;

import com.github.lechandonga.paymentledgerreconciliationjava.payment.Payment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local stand-in for the acquiring channel's reconciliation file. Rows are
 * generated from the channel's view of captured/refunded payments; tests and
 * demos may inject tampered rows to simulate channel-side discrepancies.
 * No external service is involved.
 */
@Component
public class ChannelFileSimulator {

    public record Row(String paymentId, long netAmount, String status) {
    }

    /** Rows explicitly injected to simulate channel-side data, keyed by payment id. */
    private final Map<String, Row> overrides = new LinkedHashMap<>();
    /** Extra rows the channel claims but we never booked. */
    private final List<Row> phantomRows = new ArrayList<>();
    /** Payment ids the channel file silently drops. */
    private final List<String> droppedIds = new ArrayList<>();

    public void injectOverride(Row row) {
        overrides.put(row.paymentId(), row);
    }

    public void injectPhantom(Row row) {
        phantomRows.add(row);
    }

    public void dropPayment(String paymentId) {
        droppedIds.add(paymentId);
    }

    /** Build the channel file for the given payments, applying any injected tampering. */
    public List<Row> generate(List<Payment> payments) {
        List<Row> rows = new ArrayList<>();
        for (Payment payment : payments) {
            if (droppedIds.contains(payment.getId())) {
                continue;
            }
            Row override = overrides.get(payment.getId());
            if (override != null) {
                rows.add(override);
                continue;
            }
            long net = payment.getCapturedAmount() - payment.getRefundedAmount();
            String status = payment.getRefundedAmount() > 0
                    ? (payment.refundableAmount() == 0 ? "REFUNDED" : "PARTIALLY_REFUNDED")
                    : "CAPTURED";
            rows.add(new Row(payment.getId(), net, status));
        }
        rows.addAll(phantomRows);
        return rows;
    }

    public void clear() {
        overrides.clear();
        phantomRows.clear();
        droppedIds.clear();
    }
}
