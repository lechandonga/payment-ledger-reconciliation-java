package com.github.lechandonga.paymentledgerreconciliationjava.repo;

import com.github.lechandonga.paymentledgerreconciliationjava.domain.Payment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class PaymentRepository {

    private final JdbcTemplate jdbc;

    public PaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Payment> MAPPER = (rs, n) -> {
        Payment p = new Payment();
        p.id = rs.getLong("id");
        p.paymentNo = rs.getString("payment_no");
        p.idemKey = rs.getString("idem_key");
        p.merchantId = rs.getString("merchant_id");
        p.channel = rs.getString("channel");
        p.status = rs.getString("status");
        p.amount = rs.getLong("amount");
        p.currency = rs.getString("currency");
        p.feeRateBp = rs.getInt("fee_rate_bp");
        p.capturedAmount = rs.getLong("captured_amount");
        p.refundedAmount = rs.getLong("refunded_amount");
        p.feeTotal = rs.getLong("fee_total");
        p.feeRefunded = rs.getLong("fee_refunded");
        p.settledAmount = rs.getLong("settled_amount");
        p.feeSettled = rs.getLong("fee_settled");
        p.createdAt = rs.getTimestamp("created_at").toLocalDateTime();
        p.updatedAt = rs.getTimestamp("updated_at").toLocalDateTime();
        return p;
    };

    public void insert(Payment p) {
        jdbc.update("""
                INSERT INTO payment(payment_no, idem_key, merchant_id, channel, status, amount, currency, fee_rate_bp)
                VALUES (?,?,?,?,?,?,?,?)
                """, p.paymentNo, p.idemKey, p.merchantId, p.channel, p.status, p.amount, p.currency, p.feeRateBp);
    }

    public Payment findByNo(String paymentNo) {
        List<Payment> list = jdbc.query("SELECT * FROM payment WHERE payment_no = ?", MAPPER, paymentNo);
        return list.isEmpty() ? null : list.get(0);
    }

    public Payment lockByNo(String paymentNo) {
        List<Payment> list = jdbc.query("SELECT * FROM payment WHERE payment_no = ? FOR UPDATE", MAPPER, paymentNo);
        return list.isEmpty() ? null : list.get(0);
    }

    public void applyCapture(Payment p, long newCaptured, long newFee, String status) {
        jdbc.update("""
                UPDATE payment SET captured_amount=?, fee_total=?, status=?, updated_at=CURRENT_TIMESTAMP WHERE payment_no=?
                """, newCaptured, newFee, status, p.paymentNo);
    }

    public void applyRefund(Payment p, long newRefunded, long newFeeRefunded, String status) {
        jdbc.update("""
                UPDATE payment SET refunded_amount=?, fee_refunded=?, status=?, updated_at=CURRENT_TIMESTAMP WHERE payment_no=?
                """, newRefunded, newFeeRefunded, status, p.paymentNo);
    }

    public void applySettlement(String paymentNo, long newSettled, long newFeeSettled) {
        jdbc.update("""
                UPDATE payment SET settled_amount=?, fee_settled=?, updated_at=CURRENT_TIMESTAMP WHERE payment_no=?
                """, newSettled, newFeeSettled, paymentNo);
    }

    /** 待结算候选：已发生请款且仍有未结算资金的支付单。 */
    public List<Payment> listUnsettled() {
        return jdbc.query("""
                SELECT * FROM payment
                WHERE captured_amount > 0
                  AND (captured_amount - refunded_amount) - settled_amount > 0
                ORDER BY id
                """, MAPPER);
    }

    /** 同上联机加锁版本，用于批次事务内串行化对同一支付单的结算。 */
    public List<Payment> listUnsettledForUpdate() {
        return jdbc.query("""
                SELECT * FROM payment
                WHERE captured_amount > 0
                  AND (captured_amount - refunded_amount) - settled_amount > 0
                ORDER BY id
                FOR UPDATE
                """, MAPPER);
    }
}
