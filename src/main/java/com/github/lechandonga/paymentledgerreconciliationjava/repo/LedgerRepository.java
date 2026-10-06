package com.github.lechandonga.paymentledgerreconciliationjava.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerRepository {

    public static final String CHANNEL_CASH = "CHANNEL_CASH";
    public static final String FEE_INCOME = "FEE_INCOME";
    public static final String PROCESSOR_CASH = "PROCESSOR_CASH";

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public LedgerRepository(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String payableAccount(String merchantId) {
        return "PAYABLE_" + merchantId;
    }

    public void ensureGlobalAccounts() {
        ensure(CHANNEL_CASH, "渠道资金", null);
        ensure(FEE_INCOME, "手续费收入", null);
        ensure(PROCESSOR_CASH, "机构自有资金", null);
    }

    public void ensureMerchantAccounts(String merchantId) {
        ensure(payableAccount(merchantId), "商户待结算-" + merchantId, merchantId);
    }

    private void ensure(String code, String name, String merchantId) {
        jdbc.update("""
                MERGE INTO ledger_account(account_code, account_name, merchant_id, balance, version)
                KEY(account_code) VALUES (?,?,?,0,0)
                """, code, name, merchantId);
    }

    /** 同一事务内追加一条分录并更新余额，借增贷减（负债/收入类余额为负表示贷方）。 */
    public void postEntry(String paymentNo, String refundNo, String batchNo,
                          String accountCode, String direction, long amount, String txnType) {
        jdbc.update("""
                INSERT INTO ledger_entry(payment_no, refund_no, batch_no, account_code, direction, amount, txn_type)
                VALUES (?,?,?,?,?,?,?)
                """, paymentNo, refundNo, batchNo, accountCode, direction, amount, txnType);
        long signed = "DR".equals(direction) ? amount : -amount;
        jdbc.update("UPDATE ledger_account SET balance = balance + ?, version = version + 1 WHERE account_code = ?",
                signed, accountCode);
    }

    public long getBalance(String accountCode) {
        Long v = jdbc.queryForObject("SELECT balance FROM ledger_account WHERE account_code = ?",
                Long.class, accountCode);
        return v == null ? 0L : v;
    }

    /** 全部账户余额之和；复式记账下应恒等于 0（借正贷负）。 */
    public long sumAllBalances() {
        Long v = jdbc.queryForObject("SELECT COALESCE(SUM(balance),0) FROM ledger_account", Long.class);
        return v == null ? 0L : v;
    }

    public long sumDebits() {
        Long v = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM ledger_entry WHERE direction='DR'", Long.class);
        return v == null ? 0L : v;
    }

    public long sumCredits() {
        Long v = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM ledger_entry WHERE direction='CR'", Long.class);
        return v == null ? 0L : v;
    }
}
