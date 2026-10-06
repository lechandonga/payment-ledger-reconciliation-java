package com.github.lechandonga.paymentledgerreconciliationjava.tests;

import com.github.lechandonga.paymentledgerreconciliationjava.domain.BusinessException;
import com.github.lechandonga.paymentledgerreconciliationjava.domain.ErrorCode;
import com.github.lechandonga.paymentledgerreconciliationjava.domain.Payment;
import com.github.lechandonga.paymentledgerreconciliationjava.dto.Requests;
import com.github.lechandonga.paymentledgerreconciliationjava.repo.LedgerRepository;
import com.github.lechandonga.paymentledgerreconciliationjava.repo.PaymentRepository;
import com.github.lechandonga.paymentledgerreconciliationjava.service.IdempotencyService;
import com.github.lechandonga.paymentledgerreconciliationjava.service.PaymentService;
import com.github.lechandonga.paymentledgerreconciliationjava.service.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:recon;DB_CLOSE_DELAY=-1;MODE=MySQL")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReconciliationTest {

    private static final Logger log = LoggerFactory.getLogger("BUSINESS-TEST");

    @Autowired PaymentService paymentService;
    @Autowired PaymentRepository paymentRepository;
    @Autowired ReconciliationService reconciliation;
    @Autowired IdempotencyService idempotency;
    @Autowired LedgerRepository ledger;

    private Payment readyPayment(String idem, String merchant, long amount, int feeBp, long capture) {
        Payment p = idempotency.execute(idem, idempotency.hash("CREATE", merchant, "MOCK", amount, "CNY", feeBp),
                Payment.class,
                () -> paymentService.createPayment(TestSupport.create(idem, merchant, amount, feeBp)));
        paymentService.authorize(p.paymentNo);
        paymentService.capture(p.paymentNo, capture);
        return p;
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchDetectsAllFourDiffTypesAndSettlesOnlyMatched() {
        log.info("========== 业务分组3：对账差异识别（金额不符/我方单边/渠道单边/状态不一致）==========");

        Payment matched = readyPayment("RC-1", "RM", 10000, 60, 10000);
        Payment amountDiff = readyPayment("RC-2", "RM", 2000, 0, 2000);
        Payment internalOnly = readyPayment("RC-3", "RM", 3000, 0, 3000);
        Payment statusDiff = readyPayment("RC-4", "RM", 4000, 0, 4000);
        paymentService.refund(statusDiff.paymentNo, "RC-4-R", 1000);

        List<Requests.ChannelEntry> file = List.of(
                TestSupport.entry("C1", matched.paymentNo, 9940L, "CAPTURED"),
                TestSupport.entry("C2", amountDiff.paymentNo, 1999L, "CAPTURED"),
                TestSupport.entry("C4", statusDiff.paymentNo, 3000L, "CAPTURED"),
                TestSupport.entry("C9", "PAY_NOT_EXIST", 500L, "CAPTURED"));

        Map<String, Object> report = reconciliation.runBatch("BATCH-1", List.of("RM"), file);

        List<Map<String, Object>> diffs = (List<Map<String, Object>>) report.get("diffs");
        assertEquals(4, report.get("diffCount"));
        assertEquals(1, report.get("matched"));
        assertEquals(1, report.get("settledPayments"), "只有完全匹配的单据参与结算");
        assertTrue(diffs.stream().anyMatch(d -> "AMOUNT_MISMATCH".equals(d.get("diffType"))));
        assertTrue(diffs.stream().anyMatch(d -> "INTERNAL_ONLY".equals(d.get("diffType"))));
        assertTrue(diffs.stream().anyMatch(d -> "CHANNEL_ONLY".equals(d.get("diffType"))));
        assertTrue(diffs.stream().anyMatch(d -> "STATUS_MISMATCH".equals(d.get("diffType"))));
        assertEquals(4, reconciliation.listDiffs("BATCH-1").size(), "差异持久化留存");

        Payment settled = paymentRepository.findByNo(matched.paymentNo);
        assertEquals(10000, settled.settledAmount);
        assertEquals(60, settled.feeSettled);
        Payment notSettled = paymentRepository.findByNo(amountDiff.paymentNo);
        assertEquals(0, notSettled.settledAmount, "差异单不得结算");

        log.info("[用例10-结果] 4类差异全部识别且留存, 仅匹配单结算: diffCount=4 settled=1 => PASS");
    }

    @Test
    void repeatBatchIsIdempotentAndReproducible() {
        log.info("========== 业务分组4：批次重复执行不重复入账、结论可复现 ==========");
        Payment p = readyPayment("RB-1", "RB", 1000, 0, 1000);
        var file = List.of(TestSupport.entry("CB1", p.paymentNo, 1000L, "CAPTURED"));

        Map<String, Object> first = reconciliation.runBatch("BATCH-2", List.of("RB"), file);
        long channelCashAfterFirst = ledger.getBalance(LedgerRepository.CHANNEL_CASH);
        Map<String, Object> second = reconciliation.runBatch("BATCH-2", List.of("RB"), file);

        assertEquals(((Number) first.get("settledAmount")).longValue(), ((Number) second.get("settledAmount")).longValue());
        assertEquals(first.get("diffs"), second.get("diffs"));
        assertEquals(channelCashAfterFirst, ledger.getBalance(LedgerRepository.CHANNEL_CASH),
                "重复批次不得重复入账");
        Payment after = paymentRepository.findByNo(p.paymentNo);
        assertEquals(1000, after.settledAmount);

        BusinessException mismatch = assertThrows(BusinessException.class,
                () -> reconciliation.runBatch("BATCH-2", List.of("RB"),
                        List.of(TestSupport.entry("CB1", p.paymentNo, 999L, "CAPTURED"))));
        assertEquals(ErrorCode.BATCH_INPUT_MISMATCH, mismatch.errorCode);
        log.info("[用例11-结果] 同批次重复执行回放结论不重复入账; 篡改渠道文件被拒绝: {} => PASS",
                mismatch.errorCode);
    }

    @Test
    void concurrentSameBatchOnlyOneSucceeds() throws InterruptedException {
        log.info("========== 业务分组5：同批次并发触发，仅一个生效 ==========");
        Payment p = readyPayment("CC-1", "CC", 1000, 0, 1000);
        var file = List.of(TestSupport.entry("CCX", p.paymentNo, 1000L, "CAPTURED"));

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    reconciliation.runBatch("BATCH-CONCURRENT", List.of("CC"), file);
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.errorCode == ErrorCode.BATCH_CONCURRENT) {
                        rejected.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }
        start.countDown();
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals(1, success.get(), "只有一个批次执行生效");
        assertEquals(threads - 1, rejected.get(), "其余并发请求被明确拒绝");
        Payment after = paymentRepository.findByNo(p.paymentNo);
        assertEquals(1000, after.settledAmount, "只结算一次");
        log.info("[用例12-结果] {} 个并发同批次请求: 成功1, 拒绝{} (BATCH_CONCURRENT), 无重复结算 => PASS",
                threads, rejected.get());
    }

    @Test
    void refundAfterSettlementRejectedToKeepMerchantPayableNonNegative() {
        log.info("========== 业务分组6：已结算资金退款被拒，商户待结算不为负 ==========");
        Payment p = readyPayment("RS-1", "RS", 1000, 0, 1000);
        reconciliation.runBatch("BATCH-RS", List.of("RS"),
                List.of(TestSupport.entry("CRS", p.paymentNo, 1000L, "CAPTURED")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> paymentService.refund(p.paymentNo, "RS-REF", 1));
        assertEquals(ErrorCode.MERCHANT_BALANCE_INSUFFICIENT, ex.errorCode);
        long payable = ledger.getBalance(ledger.payableAccount("RS"));
        assertEquals(0, payable, "商户待结算余额保持为 0，不允许为负");
        log.info("[用例13-结果] 结算完成后退款被拒: {}, 商户待结算={} => PASS", ex.errorCode, payable);
    }
}
