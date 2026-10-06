package com.github.lechandonga.paymentledgerreconciliationjava.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class ReconRepository {

    private final JdbcTemplate jdbc;

    public ReconRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 尝试抢占批次：仅当批次不存在时插入 PROCESSING，返回是否成功。 */
    public boolean tryAcquire(String batchNo) {
        try {
            int n = jdbc.update(
                    "INSERT INTO recon_batch(batch_no, status) VALUES (?, 'PROCESSING')", batchNo);
            return n == 1;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    public Map<String, Object> getBatch(String batchNo) {
        List<Map<String, Object>> list = jdbc.queryForList("SELECT * FROM recon_batch WHERE batch_no = ?", batchNo);
        return list.isEmpty() ? null : list.get(0);
    }

    public void finish(String batchNo, int total, int matched, int diffCount,
                       int settledPayments, long settledAmount, String report) {
        jdbc.update("""
                UPDATE recon_batch SET status='COMPLETED', total_entries=?, matched=?, diff_count=?,
                settled_payments=?, settled_amount=?, report=? WHERE batch_no=?
                """, total, matched, diffCount, settledPayments, settledAmount, report, batchNo);
    }

    public void fail(String batchNo) {
        jdbc.update("DELETE FROM recon_batch WHERE batch_no=? AND status='PROCESSING'", batchNo);
    }

    public void insertDiff(String batchNo, String paymentNo, String channelPaymentNo,
                           String diffType, String detail, Long internalAmount, Long channelAmount) {
        jdbc.update("""
                INSERT INTO recon_diff(batch_no, payment_no, channel_payment_no, diff_type, detail, internal_amount, channel_amount)
                VALUES (?,?,?,?,?,?,?)
                """, batchNo, paymentNo, channelPaymentNo, diffType, detail, internalAmount, channelAmount);
    }

    public List<Map<String, Object>> listDiffs(String batchNo) {
        return jdbc.queryForList("SELECT * FROM recon_diff WHERE batch_no = ? ORDER BY id", batchNo);
    }
}
