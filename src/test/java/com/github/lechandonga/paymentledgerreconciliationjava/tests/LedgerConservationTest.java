package com.github.lechandonga.paymentledgerreconciliationjava.tests;

import com.github.lechandonga.paymentledgerreconciliationjava.domain.BusinessException;
import com.github.lechandonga.paymentledgerreconciliationjava.domain.Payment;
import com.github.lechandonga.paymentledgerreconciliationjava.repo.LedgerRepository;
import com.github.lechandonga.paymentledgerreconciliationjava.repo.PaymentRepository;
import com.github.lechandonga.paymentledgerreconciliationjava.service.IdempotencyService;
import com.github.lechandonga.paymentledgerreconciliationjava.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ledger;DB_CLOSE_DELAY=-1;MODE=MySQL")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LedgerConservationTest {

    private static final Logger log = LoggerFactory.getLogger("BUSINESS-TEST");

    @Autowired PaymentService paymentService;
    @Autowired IdempotencyService idempotency;
    @Autowired LedgerRepository ledger;
    @Autowired PaymentRepository paymentRepository;

    @Test
    void doubleEntryAlwaysBalancedAndConservedAcrossOperationSequence() {
        log.info("========== 业务分组2：复式记账守恒（多商户/部分请款/多次退款/拒绝操作）==========");

        Payment a = newPayment("LC-A", "M_A", 10000, 100);
        Payment b = newPayment("LC-B", "M_B", 5000, 0);
        paymentService.authorize(a.paymentNo);
        paymentService.authorize(b.paymentNo);

        paymentService.capture(a.paymentNo, 4000);
        assertConserved("A 部分请款4000");
        paymentService.capture(a.paymentNo, 6000);
        paymentService.refund(a.paymentNo, "LC-R1", 2500);
        assertConserved("A 第一次退款2500");
        paymentService.refund(a.paymentNo, "LC-R2", 1500);
        assertConserved("A 第二次退款1500");

        paymentService.capture(b.paymentNo, 5000);
        paymentService.refund(b.paymentNo, "LC-R3", 1000);
        assertConserved("B 请款+部分退款");

        // 触发若干拒绝：超额退款、超额请款，均不得留账
        assertThrows(BusinessException.class, () -> paymentService.refund(b.paymentNo, "LC-R4", 99999));
        assertThrows(BusinessException.class, () -> paymentService.capture(b.paymentNo, 1));
        assertConserved("两次拒绝操作之后");

        long payableA = ledger.getBalance(ledger.payableAccount("M_A"));
        long payableB = ledger.getBalance(ledger.payableAccount("M_B"));
        assertTrue(payableA <= 0, "商户待结算为贷方（<=0），其绝对值不为负");
        assertTrue(payableB <= 0);

        // 商户待结算可退余额校验：A 已退4000/请款10000，净额待结算 = 6000 - 60 = 5940
        Payment after = paymentRepository.findByNo(a.paymentNo);
        long merchantNetPayable = -(payableA);
        assertEquals(6000L - 60L, merchantNetPayable);
        assertEquals(after.netCaptured() - after.netFee(), merchantNetPayable);

        log.info("[用例09-结果] 任意操作序列后 sum={}, 借方={}, 贷方={}, 商户A待结算={}, 商户B待结算={} => PASS",
                ledger.sumAllBalances(), ledger.sumDebits(), ledger.sumCredits(), -payableA, -payableB);
    }

    private void assertConserved(String stage) {
        assertAll(stage + " 守恒",
                () -> assertEquals(0, ledger.sumAllBalances(), stage + " 账户余额之和必须为 0"),
                () -> assertEquals(ledger.sumDebits(), ledger.sumCredits(), stage + " 借贷发生额必须相等"));
    }

    private Payment newPayment(String idem, String merchant, long amount, int feeBp) {
        return idempotency.execute(idem, idempotency.hash("CREATE", merchant, "MOCK", amount, "CNY", feeBp),
                Payment.class,
                () -> paymentService.createPayment(TestSupport.create(idem, merchant, amount, feeBp)));
    }
}
