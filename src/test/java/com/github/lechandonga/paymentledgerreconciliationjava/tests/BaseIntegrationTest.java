package com.github.lechandonga.paymentledgerreconciliationjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.paymentledgerreconciliationjava.api.IdempotencyService;
import com.github.lechandonga.paymentledgerreconciliationjava.ledger.LedgerService;
import com.github.lechandonga.paymentledgerreconciliationjava.payment.PaymentService;
import com.github.lechandonga.paymentledgerreconciliationjava.settlement.ChannelFileSimulator;
import com.github.lechandonga.paymentledgerreconciliationjava.settlement.SettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
public abstract class BaseIntegrationTest {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper om;
    @Autowired
    protected PaymentService paymentService;
    @Autowired
    protected LedgerService ledger;
    @Autowired
    protected SettlementService settlementService;
    @Autowired
    protected ChannelFileSimulator channelFile;
    @Autowired
    protected IdempotencyService idempotency;

    @BeforeEach
    void resetState() {
        paymentService.clear();
        ledger.clear();
        settlementService.clear();
        channelFile.clear();
        idempotency.clear();
    }

    protected JsonNode createPayment(String key, String merchant, long amount) throws Exception {
        return call(post("/api/payments"), Map.of(
                "idempotencyKey", key, "merchantId", merchant, "amount", amount));
    }

    protected JsonNode authorize(String key, String paymentId) throws Exception {
        return call(post("/api/payments/{id}/authorize", paymentId),
                Map.of("idempotencyKey", key));
    }

    protected JsonNode capture(String key, String paymentId, long amount) throws Exception {
        return call(post("/api/payments/{id}/capture", paymentId),
                Map.of("idempotencyKey", key, "amount", amount));
    }

    protected JsonNode refund(String key, String paymentId, long amount) throws Exception {
        return call(post("/api/payments/{id}/refund", paymentId),
                Map.of("idempotencyKey", key, "amount", amount));
    }

    protected JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                            Object body) throws Exception {
        MvcResult result = mvc.perform(builder.contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andReturn();
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? om.createObjectNode() : om.readTree(content);
    }

    protected MvcResult callRaw(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                                Object body) throws Exception {
        return mvc.perform(builder.contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andReturn();
    }
}
