package com.github.lechandonga.paymentledgerreconciliationjava.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.paymentledgerreconciliationjava.domain.BusinessException;
import com.github.lechandonga.paymentledgerreconciliationjava.domain.ErrorCode;
import com.github.lechandonga.paymentledgerreconciliationjava.domain.Payment;
import com.github.lechandonga.paymentledgerreconciliationjava.dto.Requests;
import com.github.lechandonga.paymentledgerreconciliationjava.repo.LedgerRepository;
import com.github.lechandonga.paymentledgerreconciliationjava.repo.PaymentRepository;
import com.github.lechandonga.paymentledgerreconciliationjava.repo.ReconRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    public static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    public static final String STATUS_MISMATCH = "STATUS_MISMATCH";
    public static final String INTERNAL_ONLY = "INTERNAL_ONLY";
    public static final String CHANNEL_ONLY = "CHANNEL_ONLY";

    /** 进程内正在执行的批次，用于裁决并发触发（数据库唯一键保证多实例同样互斥）。 */
    private static final java.util.Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private final ReconRepository recon;
    private final PaymentRepository payments;
    private final LedgerRepository ledger;
    private final LedgerService ledgerService;
    private final ObjectMapper objectMapper;
    private final ReconciliationService self;
    private final BatchTxExecutor batchTxExecutor;

    public ReconciliationService(ReconRepository recon, PaymentRepository payments,
                                 LedgerRepository ledger, LedgerService ledgerService,
                                 ObjectMapper objectMapper, @Lazy ReconciliationService self,
                                 BatchTxExecutor batchTxExecutor) {
        this.recon = recon;
        this.payments = payments;
        this.ledger = ledger;
        this.ledgerService = ledgerService;
        this.objectMapper = objectMapper;
        this.self = self;
        this.batchTxExecutor = batchTxExecutor;
    }

    public Map<String, Object> runBatch(String batchNo, List<String> merchantIds,
                                        List<Requests.ChannelEntry> channelEntries) {
        String inputHash = hashInput(channelEntries);
        // 进程内快速裁决：同一 JVM 内并发触发，只有第一个能进入处理
        if (!IN_FLIGHT.add(batchNo)) {
            Map<String, Object> snapshot = recon.getBatch(batchNo);
            if (snapshot == null || "PROCESSING".equals(snapshot.get("status"))) {
                throw new BusinessException(ErrorCode.BATCH_CONCURRENT,
                        "批次 " + batchNo + " 正在处理中，并发触发被拒绝");
            }
            return replayOrReject(batchNo, inputHash, snapshot);
        }
        try {
            // 抢占与执行在同一独立事务中，并发时唯一键互斥，只有一个生效
            return batchTxExecutor.acquireAndRun(batchNo, merchantIds, channelEntries, inputHash);
        } catch (BusinessException e) {
            if (e.errorCode != ErrorCode.BATCH_CONCURRENT) {
                throw e;
            }
        } finally {
            IN_FLIGHT.remove(batchNo);
        }
        // 抢占失败：批次可能正在处理（拒绝），也可能已完成（重复执行，回放首次结论）
        Map<String, Object> existing = recon.getBatch(batchNo);
        if (existing == null || "PROCESSING".equals(existing.get("status"))) {
            throw new BusinessException(ErrorCode.BATCH_CONCURRENT,
                    "批次 " + batchNo + " 并发触发，仅一个请求生效");
        }
        return replayOrReject(batchNo, inputHash, existing);
    }

    private Map<String, Object> replayOrReject(String batchNo, String inputHash, Map<String, Object> existing) {
        Map<String, Object> report = readReport((String) existing.get("report"));
        if (report != null && !inputHash.equals(report.get("inputHash"))) {
            throw new BusinessException(ErrorCode.BATCH_INPUT_MISMATCH,
                    "批次 " + batchNo + " 已有结论，但本次渠道文件内容与首次不一致");
        }
        log.info("[RECON] 批次 {} 重复执行，回放首次结论", batchNo);
        return replayView(existing);
    }

    @Transactional
    public Map<String, Object> execute(String batchNo, List<String> merchantIds,
                                       List<Requests.ChannelEntry> entries, String inputHash) {
        List<Map<String, Object>> diffs = new ArrayList<>();
        int matched = 0;

        java.util.Set<String> scope = merchantIds == null ? null : new java.util.HashSet<>(merchantIds);
        Map<String, Payment> internalByNo = new HashMap<>();
        for (Payment p : payments.listUnsettledForUpdate()) {
            if (scope == null || scope.contains(p.merchantId)) {
                internalByNo.put(p.paymentNo, p);
            }
        }

        List<String> channelMatchedKeys = new ArrayList<>();
        for (Requests.ChannelEntry e : entries) {
            Payment p = internalByNo.get(e.paymentNo());
            if (p == null) {
                addDiff(diffs, batchNo, null, e.channelPaymentNo(), CHANNEL_ONLY,
                        "渠道有记录而我方无对应待结算支付单", null, e.amount());
                continue;
            }
            channelMatchedKeys.add(e.paymentNo());
            long internalAmount = p.netCaptured();
            Long channelAmount = e.amount();
            String expectedStatus = expectedChannelStatus(p);
            // 渠道文件按净额对账（手续费由我方承担），金额口径换算后比较
            long expectedChannelAmount = internalAmount - p.netFee();
            boolean amountOk = channelAmount == null || channelAmount == expectedChannelAmount;
            boolean statusOk = e.status() == null || e.status().equals(expectedStatus);
            if (!amountOk) {
                addDiff(diffs, batchNo, p.paymentNo, e.channelPaymentNo(), AMOUNT_MISMATCH,
                        "我方净额 " + expectedChannelAmount + " 与渠道金额 " + channelAmount + " 不一致",
                        internalAmount, channelAmount);
            } else if (!statusOk) {
                addDiff(diffs, batchNo, p.paymentNo, e.channelPaymentNo(), STATUS_MISMATCH,
                        "我方状态 " + p.status + "（渠道应为 " + expectedStatus + "）与渠道状态 " + e.status() + " 不一致",
                        internalAmount, channelAmount);
            } else {
                matched++;
            }
        }

        // 我方单边：有未结算资金但渠道文件中缺失
        for (Payment p : internalByNo.values()) {
            if (!channelMatchedKeys.contains(p.paymentNo)) {
                addDiff(diffs, batchNo, p.paymentNo, null, INTERNAL_ONLY,
                        "我方有未结算支付单 " + p.paymentNo + " 但渠道文件缺失",
                        p.netCaptured(), null);
            }
        }

        // 只有完全匹配的支付单才参与结算，差异单留待人工处理，不重复入账
        List<Payment> toSettle = new ArrayList<>();
        java.util.Set<String> diffPaymentNos = new java.util.HashSet<>();
        for (Map<String, Object> d : diffs) {
            Object pn = d.get("paymentNo");
            if (pn != null) {
                diffPaymentNos.add((String) pn);
            }
        }
        long settledAmount = 0;
        for (String key : channelMatchedKeys) {
            if (!diffPaymentNos.contains(key)) {
                toSettle.add(internalByNo.get(key));
            }
        }
        for (Payment p : toSettle) {
            long grossToSettle = p.unsettledGross();
            long feeToSettle = p.unsettledFee();
            long netToSettle = grossToSettle - feeToSettle;
            ledgerService.post(p.paymentNo, null, batchNo, "SETTLEMENT",
                    ledgerService.settlementLines(p.merchantId, netToSettle, feeToSettle));
            payments.applySettlement(p.paymentNo, p.settledAmount + grossToSettle, p.feeSettled + feeToSettle);
            settledAmount += netToSettle;
            log.info("[SETTLE] 批次 {} 结算支付单 {} gross={} fee={} net={}",
                    batchNo, p.paymentNo, grossToSettle, feeToSettle, netToSettle);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("batchNo", batchNo);
        report.put("inputHash", inputHash);
        report.put("totalEntries", entries.size());
        report.put("matched", matched);
        report.put("diffCount", diffs.size());
        report.put("settledPayments", toSettle.size());
        report.put("settledAmount", settledAmount);
        report.put("diffs", diffs);

        String reportJson;
        try {
            reportJson = objectMapper.writeValueAsString(report);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
        recon.finish(batchNo, entries.size(), matched, diffs.size(), toSettle.size(), settledAmount, reportJson);
        log.info("[RECON] 批次 {} 完成 匹配={} 差异={} 结算笔数={} 结算金额={}",
                batchNo, matched, diffs.size(), toSettle.size(), settledAmount);
        return report;
    }

    private String expectedChannelStatus(Payment p) {
        return switch (p.status) {
            case "CAPTURED" -> "CAPTURED";
            case "PARTIALLY_CAPTURED" -> "PARTIALLY_CAPTURED";
            case "PARTIALLY_REFUNDED" -> "PARTIALLY_REFUNDED";
            case "REFUNDED" -> "REFUNDED";
            default -> p.status;
        };
    }

    private void addDiff(List<Map<String, Object>> diffs, String batchNo, String paymentNo,
                         String channelPaymentNo, String type, String detail,
                         Long internalAmount, Long channelAmount) {
        recon.insertDiff(batchNo, paymentNo, channelPaymentNo, type, detail, internalAmount, channelAmount);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("diffType", type);
        d.put("paymentNo", paymentNo);
        d.put("channelPaymentNo", channelPaymentNo);
        d.put("detail", detail);
        d.put("internalAmount", internalAmount);
        d.put("channelAmount", channelAmount);
        diffs.add(d);
        log.info("[RECON-DIFF] batch={} type={} payment={} channel={} detail={}",
                batchNo, type, paymentNo, channelPaymentNo, detail);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> replayView(Map<String, Object> existing) {
        Map<String, Object> report = readReport((String) existing.get("report"));
        return report != null ? report : new LinkedHashMap<>(existing);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readReport(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public List<Map<String, Object>> listDiffs(String batchNo) {
        return recon.listDiffs(batchNo);
    }

    private String hashInput(List<Requests.ChannelEntry> entries) {
        try {
            String canonical = objectMapper.writeValueAsString(entries);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
