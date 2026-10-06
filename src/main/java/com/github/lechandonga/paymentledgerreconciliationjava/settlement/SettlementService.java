package com.github.lechandonga.paymentledgerreconciliationjava.settlement;

import com.github.lechandonga.paymentledgerreconciliationjava.api.RejectionException;
import com.github.lechandonga.paymentledgerreconciliationjava.ledger.LedgerService;
import com.github.lechandonga.paymentledgerreconciliationjava.payment.Payment;
import com.github.lechandonga.paymentledgerreconciliationjava.payment.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Batch settlement + reconciliation.
 *
 * A batch settles every payment with an outstanding net payable, posts one
 * balanced settlement entry per merchant, and reconciles internal flows
 * against the local channel file. A batch id can complete only once: repeats
 * are rejected with BATCH_ALREADY_COMPLETED, and concurrent triggers are
 * serialized so that exactly one wins and the rest get BATCH_IN_PROGRESS.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final PaymentService paymentService;
    private final LedgerService ledger;
    private final ChannelFileSimulator channelFile;

    private final Set<String> completedBatches = new HashSet<>();
    private final Set<String> batchesInProgress = new HashSet<>();
    private final Map<String, ReconciliationReport> reports = new ConcurrentHashMap<>();

    public SettlementService(PaymentService paymentService, LedgerService ledger,
                             ChannelFileSimulator channelFile) {
        this.paymentService = paymentService;
        this.ledger = ledger;
        this.channelFile = channelFile;
    }

    public ReconciliationReport settle(String batchId) {
        synchronized (this) {
            if (completedBatches.contains(batchId)) {
                throw new RejectionException(RejectionException.Reason.BATCH_ALREADY_COMPLETED,
                        "batch '" + batchId + "' already completed");
            }
            if (!batchesInProgress.add(batchId)) {
                throw new RejectionException(RejectionException.Reason.BATCH_IN_PROGRESS,
                        "batch '" + batchId + "' is already running");
            }
        }
        try {
            return doSettle(batchId);
        } finally {
            synchronized (this) {
                batchesInProgress.remove(batchId);
                completedBatches.add(batchId);
            }
        }
    }

    private ReconciliationReport doSettle(String batchId) {
        List<Payment> candidates = paymentService.all().stream()
                .filter(p -> p.getCapturedAmount() > 0)
                .filter(p -> p.unsettledNet() != 0)
                .toList();

        Map<String, Long> perMerchant = new HashMap<>();
        for (Payment payment : candidates) {
            perMerchant.merge(payment.getMerchantId(), payment.unsettledNet(), Long::sum);
        }
        for (Map.Entry<String, Long> e : perMerchant.entrySet()) {
            if (e.getValue() != 0) {
                ledger.postSettlement(e.getKey(), batchId, e.getValue());
            }
        }
        for (Payment payment : candidates) {
            payment.addSettled(payment.unsettledNet());
        }
        log.info("batch {} settled {} payments across {} merchants",
                batchId, candidates.size(), perMerchant.size());

        ReconciliationReport report = reconcile(batchId,
                paymentService.all().stream().filter(p -> p.getCapturedAmount() > 0).toList());
        reports.put(batchId, report);
        return report;
    }

    /** Match internal flows against the channel file row by row, keyed by payment id. */
    private ReconciliationReport reconcile(String batchId, List<Payment> internal) {
        List<ChannelFileSimulator.Row> channelRows = channelFile.generate(internal);
        Map<String, ChannelFileSimulator.Row> channelById = new HashMap<>();
        for (ChannelFileSimulator.Row row : channelRows) {
            channelById.put(row.paymentId(), row);
        }

        int matched = 0;
        List<ReconciliationReport.Difference> diffs = new ArrayList<>();
        for (Payment payment : internal) {
            long internalNet = payment.getCapturedAmount() - payment.getRefundedAmount();
            String internalStatus = payment.getStatus().name();
            ChannelFileSimulator.Row row = channelById.remove(payment.getId());
            if (row == null) {
                diffs.add(new ReconciliationReport.Difference(DiffType.MISSING_IN_CHANNEL,
                        payment.getId(), internalNet, 0, internalStatus, null));
                continue;
            }
            if (row.netAmount() != internalNet) {
                diffs.add(new ReconciliationReport.Difference(DiffType.AMOUNT_MISMATCH,
                        payment.getId(), internalNet, row.netAmount(), internalStatus, row.status()));
            } else if (!row.status().equals(expectedChannelStatus(payment))) {
                diffs.add(new ReconciliationReport.Difference(DiffType.STATUS_MISMATCH,
                        payment.getId(), internalNet, row.netAmount(), internalStatus, row.status()));
            } else {
                matched++;
            }
        }
        for (ChannelFileSimulator.Row row : channelById.values()) {
            diffs.add(new ReconciliationReport.Difference(DiffType.MISSING_IN_INTERNAL,
                    row.paymentId(), 0, row.netAmount(), null, row.status()));
        }
        log.info("batch {} reconciled: {} matched, {} differences", batchId, matched, diffs.size());
        return new ReconciliationReport(batchId, matched, List.copyOf(diffs), Instant.now());
    }

    private static String expectedChannelStatus(Payment payment) {
        if (payment.getRefundedAmount() > 0) {
            return payment.refundableAmount() == 0 ? "REFUNDED" : "PARTIALLY_REFUNDED";
        }
        return "CAPTURED";
    }

    public ReconciliationReport report(String batchId) {
        return reports.get(batchId);
    }

    public void clear() {
        synchronized (this) {
            completedBatches.clear();
            batchesInProgress.clear();
        }
        reports.clear();
    }
}
