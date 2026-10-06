package com.github.lechandonga.paymentledgerreconciliationjava.tests.ledger;

import com.github.lechandonga.paymentledgerreconciliationjava.api.RejectionException;
import com.github.lechandonga.paymentledgerreconciliationjava.ledger.JournalEntry;
import com.github.lechandonga.paymentledgerreconciliationjava.payment.Payment;
import com.github.lechandonga.paymentledgerreconciliationjava.tests.BaseIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Business: double-entry ledger integrity.
 * Cases: every journal entry is balanced; after a randomized sequence of
 * valid and invalid operations the books stay conserved and merchant
 * payable never goes negative; rejected operations post nothing.
 */
class LedgerInvariantTest extends BaseIntegrationTest {

    @Test
    void randomizedOperationSequenceKeepsBooksConserved() {
        log.info("[CASE] randomized op sequence (200 ops incl. invalid ones)");
        Random random = new Random(42);
        int rejected = 0;
        for (int i = 0; i < 40; i++) {
            Payment p = paymentService.create("c" + i, "m" + (i % 3), 1000 + random.nextInt(9000));
            paymentService.authorize("a" + i, p.getId());
        }
        for (int i = 0; i < 200; i++) {
            Payment p = paymentService.all().stream()
                    .skip(random.nextInt(paymentService.all().size()))
                    .findFirst().orElseThrow();
            long amount = 1 + random.nextInt(3000);
            try {
                if (random.nextBoolean()) {
                    paymentService.capture("op-cap-" + i, p.getId(), amount);
                } else {
                    paymentService.refund("op-ref-" + i, p.getId(), amount);
                }
            } catch (RejectionException ex) {
                rejected++;
            }
            assertThat(ledger.isConserved()).as("conserved after op %d", i).isTrue();
            for (String merchant : new String[]{"m0", "m1", "m2"}) {
                assertThat(ledger.merchantPayable(merchant))
                        .as("merchant %s payable non-negative after op %d", merchant, i)
                        .isGreaterThanOrEqualTo(0);
            }
        }
        log.info("[PASS] 200 ops ({} rejected), books conserved, payable never negative", rejected);
    }

    @Test
    void everyJournalEntryIsBalanced() {
        log.info("[CASE] per-entry debit == credit");
        Payment p = paymentService.create("c1", "m1", 10000);
        paymentService.authorize("a1", p.getId());
        paymentService.capture("cap1", p.getId(), 6000);
        paymentService.capture("cap2", p.getId(), 4000);
        paymentService.refund("r1", p.getId(), 2500);
        settlementService.settle("b1");

        assertThat(ledger.journal()).isNotEmpty();
        for (JournalEntry entry : ledger.journal()) {
            long debit = entry.lines().stream().mapToLong(JournalEntry.Line::debit).sum();
            long credit = entry.lines().stream().mapToLong(JournalEntry.Line::credit).sum();
            assertThat(debit).as("entry %s balanced", entry.id()).isEqualTo(credit);
        }
        assertThat(ledger.merchantPayable("m1")).isZero();
        log.info("[PASS] all {} entries balanced, merchant fully settled", ledger.journal().size());
    }

    @Test
    void rejectedOperationsLeaveNoPartialBooking() {
        log.info("[CASE] rejected ops post nothing");
        Payment p = paymentService.create("c1", "m1", 5000);
        paymentService.authorize("a1", p.getId());
        paymentService.capture("cap1", p.getId(), 5000);
        int before = ledger.journal().size();

        try {
            paymentService.capture("x1", p.getId(), 1);
        } catch (RejectionException ignored) {
        }
        try {
            paymentService.refund("x2", p.getId(), 99999);
        } catch (RejectionException ignored) {
        }
        assertThat(ledger.journal().size()).isEqualTo(before);
        assertThat(ledger.isConserved()).isTrue();
        log.info("[PASS] journal unchanged after rejections");
    }
}
