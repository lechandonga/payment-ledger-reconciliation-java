package com.github.lechandonga.paymentledgerreconciliationjava.tests.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.lechandonga.paymentledgerreconciliationjava.tests.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Business: payment acceptance lifecycle.
 * Cases: happy path, partial capture, multiple refunds, fee accounting,
 * illegal transitions, over-capture, over-refund, invalid amounts,
 * and atomicity of rejected operations.
 */
class PaymentFlowTest extends BaseIntegrationTest {

    @Test
    void fullLifecycleWithPartialCaptureAndMultipleRefunds() throws Exception {
        log.info("[CASE] create -> authorize -> partial capture x2 -> refund x2");
        JsonNode created = createPayment("c1", "m1", 10000);
        String id = created.get("id").asText();
        assertThat(created.get("status").asText()).isEqualTo("CREATED");

        assertThat(authorize("a1", id).get("status").asText()).isEqualTo("AUTHORIZED");

        JsonNode cap1 = capture("cap1", id, 6000);
        assertThat(cap1.get("status").asText()).isEqualTo("PARTIALLY_CAPTURED");
        assertThat(cap1.get("capturedAmount").asLong()).isEqualTo(6000);
        assertThat(cap1.get("totalFee").asLong()).isEqualTo(60);

        JsonNode cap2 = capture("cap2", id, 4000);
        assertThat(cap2.get("status").asText()).isEqualTo("CAPTURED");
        assertThat(cap2.get("totalFee").asLong()).isEqualTo(100);

        JsonNode ref1 = refund("r1", id, 3000);
        assertThat(ref1.get("status").asText()).isEqualTo("PARTIALLY_REFUNDED");
        JsonNode ref2 = refund("r2", id, 7000);
        assertThat(ref2.get("status").asText()).isEqualTo("REFUNDED");
        assertThat(ref2.get("refundedAmount").asLong()).isEqualTo(10000);

        assertThat(ledger.isConserved()).isTrue();
        assertThat(ledger.journal()).hasSize(4); // 2 captures + 2 refunds, no duplicates
        log.info("[PASS] full lifecycle conserved, journal size=4");
    }

    @Test
    void illegalTransitionsAreRejectedWithDistinctReason() throws Exception {
        log.info("[CASE] illegal transitions: capture before auth, refund before capture, re-authorize");
        String id = createPayment("c1", "m1", 5000).get("id").asText();

        MvcResult cap = callRaw(post("/api/payments/{id}/capture", id),
                Map.of("idempotencyKey", "x1", "amount", 1000));
        assertThat(cap.getResponse().getStatus()).isEqualTo(422);
        assertThat(om.readTree(cap.getResponse().getContentAsString()).get("reason").asText())
                .isEqualTo("ILLEGAL_STATE_TRANSITION");

        MvcResult ref = callRaw(post("/api/payments/{id}/refund", id),
                Map.of("idempotencyKey", "x2", "amount", 1000));
        assertThat(om.readTree(ref.getResponse().getContentAsString()).get("reason").asText())
                .isEqualTo("ILLEGAL_STATE_TRANSITION");

        authorize("a1", id);
        MvcResult reAuth = callRaw(post("/api/payments/{id}/authorize", id),
                Map.of("idempotencyKey", "x3"));
        assertThat(om.readTree(reAuth.getResponse().getContentAsString()).get("reason").asText())
                .isEqualTo("ILLEGAL_STATE_TRANSITION");

        assertThat(ledger.journal()).isEmpty();
        log.info("[PASS] illegal transitions rejected, ledger untouched");
    }

    @Test
    void overCaptureAndOverRefundAreRejectedWithDistinctReasons() throws Exception {
        log.info("[CASE] over-capture and over-refund rejections");
        String id = createPayment("c1", "m1", 5000).get("id").asText();
        authorize("a1", id);
        capture("cap1", id, 4000);

        MvcResult overCap = callRaw(post("/api/payments/{id}/capture", id),
                Map.of("idempotencyKey", "x1", "amount", 1001));
        assertThat(om.readTree(overCap.getResponse().getContentAsString()).get("reason").asText())
                .isEqualTo("CAPTURE_EXCEEDS_AUTHORIZED");

        refund("r1", id, 3000);
        MvcResult overRef = callRaw(post("/api/payments/{id}/refund", id),
                Map.of("idempotencyKey", "x2", "amount", 1001));
        assertThat(om.readTree(overRef.getResponse().getContentAsString()).get("reason").asText())
                .isEqualTo("REFUND_EXCEEDS_CAPTURED");

        assertThat(ledger.journal()).hasSize(2);
        assertThat(ledger.isConserved()).isTrue();
        log.info("[PASS] over-capture/over-refund rejected with distinct reasons");
    }

    @Test
    void invalidAmountsAreRejected() throws Exception {
        log.info("[CASE] zero/negative amounts");
        MvcResult create = callRaw(post("/api/payments"),
                Map.of("idempotencyKey", "c1", "merchantId", "m1", "amount", 0));
        assertThat(om.readTree(create.getResponse().getContentAsString()).get("reason").asText())
                .isEqualTo("INVALID_AMOUNT");

        String id = createPayment("c2", "m1", 5000).get("id").asText();
        authorize("a1", id);
        MvcResult cap = callRaw(post("/api/payments/{id}/capture", id),
                Map.of("idempotencyKey", "x1", "amount", -100));
        assertThat(om.readTree(cap.getResponse().getContentAsString()).get("reason").asText())
                .isEqualTo("INVALID_AMOUNT");
        log.info("[PASS] invalid amounts rejected");
    }

    @Test
    void unknownPaymentReturnsNotFound() throws Exception {
        log.info("[CASE] unknown payment id");
        MvcResult res = callRaw(post("/api/payments/{id}/authorize", "nope"),
                Map.of("idempotencyKey", "x1"));
        assertThat(res.getResponse().getStatus()).isEqualTo(404);
        assertThat(om.readTree(res.getResponse().getContentAsString()).get("reason").asText())
                .isEqualTo("PAYMENT_NOT_FOUND");
        log.info("[PASS] unknown payment -> 404 PAYMENT_NOT_FOUND");
    }
}
