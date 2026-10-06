package com.github.lechandonga.paymentledgerreconciliationjava.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> find(String idemKey) {
        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT * FROM idempotent_request WHERE idem_key = ?", idemKey);
        return list.isEmpty() ? null : list.get(0);
    }

    public void insert(String idemKey, String requestHash, String operation, int code, String body) {
        jdbc.update("""
                INSERT INTO idempotent_request(idem_key, request_hash, operation, response_code, response_body)
                VALUES (?,?,?,?,?)
                """, idemKey, requestHash, operation, code, body);
    }
}
