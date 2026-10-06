package com.github.lechandonga.paymentledgerreconciliationjava.repo;

import com.github.lechandonga.paymentledgerreconciliationjava.domain.RefundOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class RefundRepository {

    private final JdbcTemplate jdbc;

    public RefundRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RefundOrder> MAPPER = (rs, n) -> {
        RefundOrder r = new RefundOrder();
        r.id = rs.getLong("id");
        r.refundNo = rs.getString("refund_no");
        r.paymentNo = rs.getString("payment_no");
        r.idemKey = rs.getString("idem_key");
        r.amount = rs.getLong("amount");
        r.feeReversed = rs.getLong("fee_reversed");
        r.status = rs.getString("status");
        r.createdAt = rs.getTimestamp("created_at").toLocalDateTime();
        return r;
    };

    public void insert(RefundOrder r) {
        jdbc.update("""
                INSERT INTO refund_order(refund_no, payment_no, idem_key, amount, fee_reversed, status)
                VALUES (?,?,?,?,?,?)
                """, r.refundNo, r.paymentNo, r.idemKey, r.amount, r.feeReversed, r.status);
    }

    public RefundOrder findByIdemKey(String idemKey) {
        List<RefundOrder> list = jdbc.query("SELECT * FROM refund_order WHERE idem_key = ?", MAPPER, idemKey);
        return list.isEmpty() ? null : list.get(0);
    }
}
