package com.github.lechandonga.paymentledgerreconciliationjava.api;

import com.github.lechandonga.paymentledgerreconciliationjava.settlement.ReconciliationReport;
import com.github.lechandonga.paymentledgerreconciliationjava.settlement.SettlementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settlements")
public class SettlementController {

    private final SettlementService settlements;

    public SettlementController(SettlementService settlements) {
        this.settlements = settlements;
    }

    @PostMapping("/{batchId}")
    public ReconciliationReport settle(@PathVariable String batchId) {
        return settlements.settle(batchId);
    }

    @GetMapping("/{batchId}")
    public ResponseEntity<ReconciliationReport> report(@PathVariable String batchId) {
        ReconciliationReport report = settlements.report(batchId);
        return report == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(report);
    }
}
