package com.github.lechandonga.paymentledgerreconciliationjava.service;

import com.github.lechandonga.paymentledgerreconciliationjava.repo.LedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 复式记账规则（金额单位：分）：
 * 请款 gross、手续费 fee（net = gross - fee）：
 *   借 CHANNEL_CASH gross            渠道资金增加
 *   贷 PAYABLE_商户 net               商户待结算增加
 *   贷 FEE_INCOME fee                手续费收入
 * 退款 gross、退手续费 feeReversed：
 *   借 PAYABLE_商户 (gross-feeReversed)
 *   借 FEE_INCOME feeReversed        手续费随退款冲回
 *   贷 CHANNEL_CASH gross
 * 结算（打款给商户 net，并把手续费从渠道资金划转至机构自有资金）：
 *   借 PAYABLE_商户 net
 *   贷 CHANNEL_CASH net
 *   借 PROCESSOR_CASH fee
 *   贷 CHANNEL_CASH fee
 * 每组分录借贷必相等；校验失败抛异常，由外层事务整体回滚。
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final LedgerRepository ledger;

    public LedgerService(LedgerRepository ledger) {
        this.ledger = ledger;
    }

    public record Line(String account, String direction, long amount) {
    }

    public List<Line> captureLines(String merchantId, long gross, long fee) {
        String payable = ledger.payableAccount(merchantId);
        long net = gross - fee;
        return List.of(
                new Line(LedgerRepository.CHANNEL_CASH, "DR", gross),
                new Line(payable, "CR", net),
                new Line(LedgerRepository.FEE_INCOME, "CR", fee));
    }

    public List<Line> refundLines(String merchantId, long gross, long feeReversed) {
        String payable = ledger.payableAccount(merchantId);
        long net = gross - feeReversed;
        return List.of(
                new Line(payable, "DR", net),
                new Line(LedgerRepository.FEE_INCOME, "DR", feeReversed),
                new Line(LedgerRepository.CHANNEL_CASH, "CR", gross));
    }

    public List<Line> settlementLines(String merchantId, long netPayable, long feePayable) {
        String payable = ledger.payableAccount(merchantId);
        List<Line> lines = new ArrayList<>();
        if (netPayable > 0) {
            lines.add(new Line(payable, "DR", netPayable));
            lines.add(new Line(LedgerRepository.CHANNEL_CASH, "CR", netPayable));
        }
        if (feePayable > 0) {
            lines.add(new Line(LedgerRepository.PROCESSOR_CASH, "DR", feePayable));
            lines.add(new Line(LedgerRepository.CHANNEL_CASH, "CR", feePayable));
        }
        return lines;
    }

    public void post(String paymentNo, String refundNo, String batchNo, String txnType, List<Line> lines) {
        long dr = lines.stream().filter(l -> "DR".equals(l.direction())).mapToLong(Line::amount).sum();
        long cr = lines.stream().filter(l -> "CR".equals(l.direction())).mapToLong(Line::amount).sum();
        if (dr != cr) {
            throw new IllegalStateException("借贷不平衡: DR=" + dr + ", CR=" + cr);
        }
        if (dr == 0) {
            return;
        }
        for (Line line : lines) {
            ledger.postEntry(paymentNo, refundNo, batchNo, line.account(), line.direction(), line.amount(), txnType);
        }
        log.info("[LEDGER] txn={} payment={} refund={} batch={} dr={} cr={} lines={}",
                txnType, paymentNo, refundNo, batchNo, dr, cr, lines.size());
    }
}
