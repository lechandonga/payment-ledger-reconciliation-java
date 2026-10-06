package com.github.lechandonga.paymentledgerreconciliationjava.tests.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.lechandonga.paymentledgerreconciliationjava.tests.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Business: idempotent acceptance.
 * Cases: replay with identical parameters returns the first result without
 * re-executing; same key with different parameters is rejected.
 */
class IdempotencyTest extends BaseIntegrationTest {

    @Test
    void replayWithSameParametersReturnsFirstResult() throws Exception {
        log.info("[CASE] idempotent replay of create/capture/refund");
        JsonNode first = createPayment("k-create", "m1", 8000);
        JsonNode replay = createPayment("k-create", "m1", 8000);
        assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(paymentService.all()).hasSize(1);

        String id = first.get("id").asText();
        authorize("k-auth", id);

        JsonNode cap1 = capture("k-cap", id, 5000);
        JsonNode cap2 = capture("k-cap", id, 5000);
        assertThat(cap2.get("capturedAmount").asLong()).isEqualTo(5000);
        assertThat(cap1.toString()).isEqualTo(cap2.toString());

        refund("k-ref", id, 2000);
        JsonNode refReplay = refund("k-ref", id, 2000);
        assertThat(refReplay.get("refundedAmount").asLong()).isEqualTo(2000);

        assertThat(ledger.journal()).hasSize(2);
        assertThat(ledger.isConserved()).isTrue();
        log.info("[PASS] replays returned first results, no double booking");
    }

    @Test
    void sameKeyWithDifferentParametersIsRejected() throws Exception {
        log.info("[CASE] idempotency key conflict");
        createPayment("k1", "m1", 8000);
        MvcResult conflict = callRaw(post("/api/payments"),
                Map.of("idempotencyKey", "k1", "merchantId", "m1", "amount", 9000));
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(om.readTree(conflict.getResponse().getContentAsString()).get("reason").asText())
                .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");

        String id = paymentService.all().iterator().next().getId();
        authorize("k2", id);
        MvcResult conflict2 = callRaw(post("/api/payments/{id}/capture", id),
                Map.of("idempotencyKey", "k2", "amount", 1000));
        assertThat(conflict2.getResponse().getStatus()).isEqualTo(409);
        assertThat(om.readTree(conflict2.getResponse().getContentAsString()).get("reason").asText())
                .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        log.info("[PASS] key reuse with different params -> 409 IDEMPOTENCY_KEY_CONFLICT");
    }
}
