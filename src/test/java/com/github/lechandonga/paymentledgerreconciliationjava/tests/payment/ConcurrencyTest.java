package com.github.lechandonga.paymentledgerreconciliationjava.tests.payment;

import com.github.lechandonga.paymentledgerreconciliationjava.api.RejectionException;
import com.github.lechandonga.paymentledgerreconciliationjava.payment.Payment;
import com.github.lechandonga.paymentledgerreconciliationjava.tests.BaseIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Business: concurrency safety.
 * Cases: parallel captures never exceed the authorized amount; parallel
 * settlement triggers of the same batch let exactly one win.
 */
class ConcurrencyTest extends BaseIntegrationTest {

    @Test
    void parallelCapturesNeverOverCapture() throws Exception {
        log.info("[CASE] 8 parallel captures of 2000 on a 10000 authorization");
        Payment payment = paymentService.create("c1", "m1", 10000);
        paymentService.authorize("a1", payment.getId());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String key = "cap-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    paymentService.capture(key, payment.getId(), 2000);
                    return "OK";
                } catch (RejectionException ex) {
                    return ex.getReason().name();
                }
            }));
        }
        start.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();

        long ok = results.stream().filter("OK"::equals).count();
        assertThat(ok).isEqualTo(5);
        assertThat(results).allMatch(r -> r.equals("OK")
                || r.equals("CAPTURE_EXCEEDS_AUTHORIZED") || r.equals("ILLEGAL_STATE_TRANSITION"));
        assertThat(payment.getCapturedAmount()).isEqualTo(10000);
        assertThat(ledger.isConserved()).isTrue();
        log.info("[PASS] exactly 5 captures won, rest rejected, ledger conserved");
    }

    @Test
    void parallelBatchTriggersLetExactlyOneWin() throws Exception {
        log.info("[CASE] 6 parallel triggers of the same settlement batch");
        Payment payment = paymentService.create("c1", "m1", 5000);
        paymentService.authorize("a1", payment.getId());
        paymentService.capture("cap1", payment.getId(), 5000);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    settlementService.settle("batch-1");
                    return "OK";
                } catch (RejectionException ex) {
                    return ex.getReason().name();
                }
            }));
        }
        start.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();

        long ok = results.stream().filter("OK"::equals).count();
        assertThat(ok).isEqualTo(1);
        assertThat(results).allMatch(r -> r.equals("OK")
                || r.equals("BATCH_IN_PROGRESS") || r.equals("BATCH_ALREADY_COMPLETED"));
        long settlementEntries = ledger.journal().stream()
                .filter(e -> e.type().equals("SETTLEMENT")).count();
        assertThat(settlementEntries).isEqualTo(1);
        log.info("[PASS] exactly one batch trigger won, one settlement entry posted");
    }
}
