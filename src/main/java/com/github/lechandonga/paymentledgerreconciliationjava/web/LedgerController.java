package com.github.lechandonga.paymentledgerreconciliationjava.web;

import com.github.lechandonga.paymentledgerreconciliationjava.repo.LedgerRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ledger")
public class LedgerController {

    private final LedgerRepository ledger;

    public LedgerController(LedgerRepository ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sumBalances", ledger.sumAllBalances());
        m.put("sumDebits", ledger.sumDebits());
        m.put("sumCredits", ledger.sumCredits());
        m.put("channelCash", ledger.getBalance(LedgerRepository.CHANNEL_CASH));
        m.put("feeIncome", ledger.getBalance(LedgerRepository.FEE_INCOME));
        m.put("processorCash", ledger.getBalance(LedgerRepository.PROCESSOR_CASH));
        return m;
    }
}
