package com.github.lechandonga.paymentledgerreconciliationjava.ledger;

import com.github.lechandonga.paymentledgerreconciliationjava.api.RejectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Double-entry ledger. Every business event posts exactly one balanced
 * {@link JournalEntry}; validation happens before posting so a rejected
 * operation never leaves a partial booking.
 *
 * Posting rules:
 *   capture  Dr CHANNEL_CLEARING gross / Cr MERCHANT_PAYABLE net / Cr FEE_REVENUE fee
 *   refund   Dr MERCHANT_PAYABLE / Cr CHANNEL_CLEARING
 *   settle   Dr MERCHANT_PAYABLE / Cr MERCHANT_CASH
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private record AccountKey(AccountType type, String ownerId) {
    }

    private final List<JournalEntry> journal = new CopyOnWriteArrayList<>();
    private final Map<AccountKey, Long> balances = new ConcurrentHashMap<>();

    public synchronized JournalEntry postCapture(String merchantId, String paymentId, long gross, long fee) {
        return post(JournalEntry.of("CAPTURE", paymentId, List.of(
                new JournalEntry.Line(AccountType.CHANNEL_CLEARING, "channel", gross, 0),
                new JournalEntry.Line(AccountType.MERCHANT_PAYABLE, merchantId, 0, gross - fee),
                new JournalEntry.Line(AccountType.FEE_REVENUE, "platform", 0, fee))));
    }

    public synchronized JournalEntry postRefund(String merchantId, String paymentId, long amount) {
        return post(JournalEntry.of("REFUND", paymentId, List.of(
                new JournalEntry.Line(AccountType.MERCHANT_PAYABLE, merchantId, amount, 0),
                new JournalEntry.Line(AccountType.CHANNEL_CLEARING, "channel", 0, amount))));
    }

    public synchronized JournalEntry postSettlement(String merchantId, String batchId, long amount) {
        if (amount > merchantPayable(merchantId)) {
            throw new RejectionException(RejectionException.Reason.SETTLEMENT_BALANCE_NEGATIVE,
                    "settlement " + amount + " would drive merchant payable negative for " + merchantId);
        }
        return post(JournalEntry.of("SETTLEMENT", batchId, List.of(
                new JournalEntry.Line(AccountType.MERCHANT_PAYABLE, merchantId, amount, 0),
                new JournalEntry.Line(AccountType.MERCHANT_CASH, merchantId, 0, amount))));
    }

    private JournalEntry post(JournalEntry entry) {
        for (JournalEntry.Line line : entry.lines()) {
            AccountKey key = new AccountKey(line.account(), line.ownerId());
            balances.merge(key, line.debit() - line.credit(), Long::sum);
        }
        journal.add(entry);
        log.info("posted {} entry {} ref={}", entry.type(), entry.id(), entry.referenceId());
        return entry;
    }

    /**
     * Signed balance as debit-minus-credit: debit-normal accounts (assets) are
     * positive, credit-normal accounts (liabilities/income) are negative, so
     * the signed balances of all accounts always sum to zero.
     */
    public long signedBalance(AccountType type, String ownerId) {
        return balances.getOrDefault(new AccountKey(type, ownerId), 0L);
    }

    public long merchantPayable(String merchantId) {
        return -signedBalance(AccountType.MERCHANT_PAYABLE, merchantId);
    }

    /** Global conservation invariant: signed balances over all accounts sum to zero. */
    public boolean isConserved() {
        long sum = 0;
        for (Map.Entry<AccountKey, Long> e : balances.entrySet()) {
            sum += signedBalance(e.getKey().type(), e.getKey().ownerId());
        }
        return sum == 0;
    }

    public List<JournalEntry> journal() {
        return List.copyOf(journal);
    }

    public void clear() {
        journal.clear();
        balances.clear();
    }
}
