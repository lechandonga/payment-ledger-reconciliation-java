package com.github.lechandonga.paymentledgerreconciliationjava.api;

import com.github.lechandonga.paymentledgerreconciliationjava.ledger.JournalEntry;
import com.github.lechandonga.paymentledgerreconciliationjava.ledger.LedgerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ledger")
public class LedgerController {

    private final LedgerService ledger;

    public LedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/journal")
    public List<JournalEntry> journal() {
        return ledger.journal();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("conserved", ledger.isConserved());
    }
}
