package com.github.lechandonga.paymentledgerreconciliationjava.tests.settlement;

import com.github.lechandonga.paymentledgerreconciliationjava.api.RejectionException;
import com.github.lechandonga.paymentledgerreconciliationjava.payment.Payment;
import com.github.lechandonga.paymentledgerreconciliationjava.settlement.ChannelFileSimulator;
import com.github.lechandonga.paymentledgerreconciliationjava.settlement.DiffType;
import com.github.lechandonga.paymentledgerreconciliationjava.settlement.ReconciliationReport;
import com.github.lechandonga.paymentledgerreconciliationjava.tests.BaseIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Business: batch settlement and reconciliation.
 * Cases: clean batch, amount mismatch, missing-on-either-side, status
 * mismatch, batch replay rejection, report persistence and reproducibility.
 */
class ReconciliationTest extends BaseIntegrationTest {

    private Payment capturedPayment(String key, String merchant, long amount) {
        Payment p = paymentService.create("c-" + key, merchant, amount);
        paymentService.authorize("a-" + key, p.getId());
        paymentService.capture("cap-" + key, p.getId(), amount);
        return p;
    }

    @Test
    void cleanBatchMatchesAllRows() {
        log.info("[CASE] clean batch: 3 payments fully matched");
        capturedPayment("1", "m1", 5000);
        capturedPayment("2", "m1", 3000);
        Payment p3 = capturedPayment("3", "m2", 8000);
        paymentService.refund("r-3", p3.getId(), 2000);

        ReconciliationReport report = settlementService.settle("batch-clean");
        assertThat(report.clean()).isTrue();
        assertThat(report.matchedCount()).isEqualTo(3);
        assertThat(ledger.merchantPayable("m1")).isZero();
        assertThat(ledger.merchantPayable("m2")).isZero();
        assertThat(ledger.isConserved()).isTrue();
        log.info("[PASS] clean batch matched=3, merchants fully settled");
    }

    @Test
    void amountMismatchIsReported() {
        log.info("[CASE] channel reports a different amount");
        Payment p = capturedPayment("1", "m1", 5000);
        channelFile.injectOverride(new ChannelFileSimulator.Row(p.getId(), 4900, "CAPTURED"));

        ReconciliationReport report = settlementService.settle("batch-amt");
        assertThat(report.differences()).hasSize(1);
        ReconciliationReport.Difference diff = report.differences().get(0);
        assertThat(diff.type()).isEqualTo(DiffType.AMOUNT_MISMATCH);
        assertThat(diff.internalAmount()).isEqualTo(5000);
        assertThat(diff.channelAmount()).isEqualTo(4900);
        log.info("[PASS] AMOUNT_MISMATCH detected internal=5000 channel=4900");
    }

    @Test
    void missingOnEitherSideIsReported() {
        log.info("[CASE] our-side-only and channel-side-only rows");
        Payment ours = capturedPayment("1", "m1", 5000);
        channelFile.dropPayment(ours.getId());
        channelFile.injectPhantom(new ChannelFileSimulator.Row("phantom-1", 7777, "CAPTURED"));

        ReconciliationReport report = settlementService.settle("batch-missing");
        assertThat(report.differences()).hasSize(2);
        assertThat(report.differences())
                .anySatisfy(d -> {
                    assertThat(d.type()).isEqualTo(DiffType.MISSING_IN_CHANNEL);
                    assertThat(d.paymentId()).isEqualTo(ours.getId());
                })
                .anySatisfy(d -> {
                    assertThat(d.type()).isEqualTo(DiffType.MISSING_IN_INTERNAL);
                    assertThat(d.paymentId()).isEqualTo("phantom-1");
                });
        log.info("[PASS] MISSING_IN_CHANNEL and MISSING_IN_INTERNAL detected");
    }

    @Test
    void statusMismatchIsReported() {
        log.info("[CASE] same amount but different status");
        Payment p = capturedPayment("1", "m1", 5000);
        channelFile.injectOverride(new ChannelFileSimulator.Row(p.getId(), 5000, "REFUNDED"));

        ReconciliationReport report = settlementService.settle("batch-status");
        assertThat(report.differences()).hasSize(1);
        assertThat(report.differences().get(0).type()).isEqualTo(DiffType.STATUS_MISMATCH);
        log.info("[PASS] STATUS_MISMATCH detected");
    }

    @Test
    void batchReplayIsRejectedAndReportIsReproducible() {
        log.info("[CASE] batch replay rejected, stored report reproducible");
        capturedPayment("1", "m1", 5000);
        ReconciliationReport first = settlementService.settle("batch-x");

        assertThatThrownBy(() -> settlementService.settle("batch-x"))
                .isInstanceOf(RejectionException.class)
                .extracting(ex -> ((RejectionException) ex).getReason())
                .isEqualTo(RejectionException.Reason.BATCH_ALREADY_COMPLETED);

        ReconciliationReport stored = settlementService.report("batch-x");
        assertThat(stored).isNotNull();
        assertThat(stored.matchedCount()).isEqualTo(first.matchedCount());
        assertThat(stored.differences()).isEqualTo(first.differences());
        long settlementEntries = ledger.journal().stream()
                .filter(e -> e.type().equals("SETTLEMENT")).count();
        assertThat(settlementEntries).isEqualTo(1);
        log.info("[PASS] replay rejected with BATCH_ALREADY_COMPLETED, report reproducible");
    }
}
