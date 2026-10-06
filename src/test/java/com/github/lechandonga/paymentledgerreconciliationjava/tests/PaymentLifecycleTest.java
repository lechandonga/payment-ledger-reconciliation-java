package com.github.lechandonga.paymentledgerreconciliationjava.tests;

import com.github.lechandonga.paymentledgerreconciliationjava.domain.BusinessException;
import com.github.lechandonga.paymentledgerreconciliationjava.domain.ErrorCode;
import com.github.lechandonga.paymentledgerreconciliationjava.domain.Payment;
import com.github.lechandonga.paymentledgerreconciliationjava.dto.Requests;
import com.github.lechandonga.paymentledgerreconciliationjava.repo.PaymentRepository;
import com.github.lechandonga.paymentledgerreconciliationjava.service.IdempotencyService;
import com.github.lechandonga.paymentledgerreconciliationjava.service.PaymentService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:lifecycle;DB_CLOSE_DELAY=-1;MODE=MySQL")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentLifecycleTest {

    private static final Logger log = LoggerFactory.getLogger("BUSINESS-TEST");

    @Autowired PaymentService paymentService;
    @Autowired IdempotencyService idempotency;
    @Autowired PaymentRepository paymentRepository;

    private String paymentNo;

    @BeforeAll
    void scenario() {
        log.info("========== 业务分组1：支付受理与状态流转（创建/授权/部分请款/多次退款）==========");
    }

    @Test
    @Order(1)
    void case01_createAuthorizePartialCapture() {
        Payment created = idempotency.execute("IDEM-CREATE-1",
                idempotency.hash("CREATE", "M1", "MOCK", 10000L, "CNY", 60),
                Payment.class,
                () -> paymentService.createPayment(TestSupport.create("IDEM-CREATE-1", "M1", 10000, 60)));
        paymentNo = created.paymentNo;
        Payment authorized = paymentService.authorize(paymentNo);
        assertEquals("AUTHORIZED", authorized.status);

        Payment partial = idempotency.execute("IDEM-CAP-1",
                idempotency.hash("CAPTURE", paymentNo, 4000L),
                Payment.class,
                () -> paymentService.capture(paymentNo, 4000));
        assertAll("部分请款",
                () -> assertEquals("PARTIALLY_CAPTURED", partial.status),
                () -> assertEquals(4000, partial.capturedAmount),
                () -> assertEquals(24, partial.feeTotal));
        log.info("[用例01-结果] 创建->授权->部分请款4000: status={}, fee={} => PASS",
                partial.status, partial.feeTotal);
    }

    @Test
    @Order(2)
    void case02_duplicateCaptureIdemKeyReplaysSameResult() {
        Payment again = idempotency.execute("IDEM-CAP-1",
                idempotency.hash("CAPTURE", paymentNo, 4000L),
                Payment.class,
                () -> {
                    throw new AssertionError("重复请求不应再次执行业务");
                });
        assertEquals(4000, again.capturedAmount);
        log.info("[用例02-结果] 相同幂等键+相同参数重复请款: 回放首次结果 captured={} => PASS", again.capturedAmount);
    }

    @Test
    @Order(3)
    void case03_sameIdemKeyDifferentParamsRejected() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                idempotency.execute("IDEM-CAP-1",
                        idempotency.hash("CAPTURE", paymentNo, 5000L),
                        Payment.class,
                        () -> paymentService.capture(paymentNo, 5000)));
        assertEquals(ErrorCode.IDEMPOTENT_PARAM_MISMATCH, ex.errorCode);
        log.info("[用例03-结果] 相同幂等键但金额不同: 拒绝原因={} => PASS", ex.errorCode);
    }

    @Test
    @Order(4)
    void case04_captureRemainderThenMultipleRefunds() {
        Payment full = paymentService.capture(paymentNo, 6000);
        assertEquals("CAPTURED", full.status);
        assertEquals(60, full.feeTotal);

        var r1 = paymentService.refund(paymentNo, "IDEM-REF-1", 3000);
        Payment p1 = read();
        assertEquals("PARTIALLY_REFUNDED", p1.status);
        assertEquals(18, r1.feeReversed);

        var r2 = paymentService.refund(paymentNo, "IDEM-REF-2", 5000);
        Payment p2 = read();
        assertEquals("PARTIALLY_REFUNDED", p2.status);
        assertEquals(8000, p2.refundedAmount);
        assertEquals(30, r2.feeReversed, "第二次退款按比例冲回手续费 5000*60/10000=30");
        // 最后一笔退清剩余 2000，末次退款承担舍入差，手续费全部冲回
        var r3 = paymentService.refund(paymentNo, "IDEM-REF-3", 2000);
        Payment p3 = read();
        assertEquals("REFUNDED", p3.status);
        assertEquals(60, p3.feeRefunded, "手续费应随退款全部冲回，不重不漏");
        assertEquals(12, r3.feeReversed);
        log.info("[用例04-结果] 全额请款后两次退款3000+5000(超额边界=剩余可退): feeRev1={}, feeRev2={}, status={} => PASS",
                r1.feeReversed, r2.feeReversed, p2.status);
    }

    @Test
    @Order(5)
    void case05_illegalTransitionsAreRejected() {
        BusinessException authorizeTwice = assertThrows(BusinessException.class,
                () -> paymentService.authorize(paymentNo));
        BusinessException refundAfterFullRefund = assertThrows(BusinessException.class,
                () -> paymentService.refund(paymentNo, "IDEM-REF-FULL-X", 1));
        assertAll("非法流转可区分",
                () -> assertEquals(ErrorCode.ILLEGAL_TRANSITION, authorizeTwice.errorCode),
                () -> assertEquals(ErrorCode.ILLEGAL_TRANSITION, refundAfterFullRefund.errorCode));
        log.info("[用例05-结果] 已退款单再次授权/退款: 均拒绝 ILLEGAL_TRANSITION => PASS");
    }

    @Test
    @Order(6)
    void case06_overCaptureRejectedWithDistinctReason() {
        Payment p = idempotency.execute("IDEM-CREATE-OC",
                idempotency.hash("CREATE", "M2", "MOCK", 1000L, "CNY", 0),
                Payment.class,
                () -> paymentService.createPayment(TestSupport.create("IDEM-CREATE-OC", "M2", 1000, 0)));
        paymentService.authorize(p.paymentNo);
        paymentService.capture(p.paymentNo, 600);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> paymentService.capture(p.paymentNo, 500));
        assertEquals(ErrorCode.CAPTURE_EXCEEDS_AUTH, ex.errorCode);
        log.info("[用例06-结果] 部分请款600后再请款500(授权额1000): 拒绝原因={} => PASS", ex.errorCode);
    }

    @Test
    @Order(7)
    void case07_overRefundRejectedWithDistinctReason() {
        Payment p = idempotency.execute("IDEM-CREATE-OR",
                idempotency.hash("CREATE", "M3", "MOCK", 2000L, "CNY", 0),
                Payment.class,
                () -> paymentService.createPayment(TestSupport.create("IDEM-CREATE-OR", "M3", 2000, 0)));
        paymentService.authorize(p.paymentNo);
        paymentService.capture(p.paymentNo, 800);
        paymentService.refund(p.paymentNo, "IDEM-REF-OR", 500);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> paymentService.refund(p.paymentNo, "IDEM-REF-OR2", 400));
        assertEquals(ErrorCode.REFUND_EXCEEDS_CAPTURED, ex.errorCode);
        log.info("[用例07-结果] 请款800退500后再退400: 拒绝原因={} => PASS", ex.errorCode);
    }

    @Test
    @Order(8)
    void case08_rejectedOperationLeavesNoPartialLedger() {
        Payment before = read();
        assertThrows(BusinessException.class,
                () -> paymentService.refund(paymentNo, "IDEM-REF-BAD-2", 100));
        Payment after = read();
        assertAll("被拒操作不改账不改状态",
                () -> assertEquals(before.status, after.status),
                () -> assertEquals(before.refundedAmount, after.refundedAmount),
                () -> assertEquals(before.feeRefunded, after.feeRefunded));
        log.info("[用例08-结果] 已全额退款单再退100被拒: 无半截账，状态金额不变 => PASS");
    }

    private Payment read() {
        return paymentRepository.findByNo(paymentNo);
    }
}
