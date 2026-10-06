package com.github.lechandonga.paymentledgerreconciliationjava.settlement;

import java.time.Instant;
import java.util.List;

/**
 * Persisted, reproducible outcome of reconciling one settlement batch.
 */
public record ReconciliationReport(String batchId,
                                   int matchedCount,
                                   List<Difference> differences,
                                   Instant createdAt) {

    public record Difference(DiffType type, String paymentId,
                             long internalAmount, long channelAmount,
                             String internalStatus, String channelStatus) {
    }

    public boolean clean() {
        return differences.isEmpty();
    }
}
