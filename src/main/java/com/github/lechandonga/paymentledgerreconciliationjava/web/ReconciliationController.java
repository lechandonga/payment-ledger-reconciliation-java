package com.github.lechandonga.paymentledgerreconciliationjava.web;

import com.github.lechandonga.paymentledgerreconciliationjava.dto.Requests;
import com.github.lechandonga.paymentledgerreconciliationjava.service.ReconciliationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/reconciliation")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    /** 用本地模拟的渠道对账文件跑一批对账+结算。 */
    @PostMapping("/batches")
    public Map<String, Object> runBatch(@RequestBody Requests.ReconRequest req) {
        return reconciliationService.runBatch(req.batchNo(), req.merchantIds(), req.channelEntries());
    }

    @GetMapping("/batches/{batchNo}/diffs")
    public Map<String, Object> diffs(@PathVariable String batchNo) {
        return Map.of("batchNo", batchNo, "diffs", reconciliationService.listDiffs(batchNo));
    }
}
