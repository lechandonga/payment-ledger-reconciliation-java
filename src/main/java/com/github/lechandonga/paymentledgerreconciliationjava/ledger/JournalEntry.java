package com.github.lechandonga.paymentledgerreconciliationjava.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One balanced double-entry booking: total debits always equal total credits.
 */
public record JournalEntry(String id, String type, String referenceId,
                           List<Line> lines, Instant bookedAt) {

    public record Line(AccountType account, String ownerId, long debit, long credit) {
    }

    public static JournalEntry of(String type, String referenceId, List<Line> lines) {
        long debit = lines.stream().mapToLong(Line::debit).sum();
        long credit = lines.stream().mapToLong(Line::credit).sum();
        if (debit != credit) {
            throw new IllegalStateException("unbalanced entry: debit " + debit + " != credit " + credit);
        }
        return new JournalEntry(UUID.randomUUID().toString(), type, referenceId, List.copyOf(lines), Instant.now());
    }
}
