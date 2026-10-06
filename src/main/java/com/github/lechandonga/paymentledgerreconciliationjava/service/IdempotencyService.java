package com.github.lechandonga.paymentledgerreconciliationjava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.paymentledgerreconciliationjava.domain.BusinessException;
import com.github.lechandonga.paymentledgerreconciliationjava.domain.ErrorCode;
import com.github.lechandonga.paymentledgerreconciliationjava.repo.IdempotencyRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 幂等控制：
 * 1. idemKey 首次出现 -> 执行业务并保存「请求参数哈希 + 响应」；
 * 2. idemKey 重复且参数哈希一致 -> 不重复执行，直接回放首次响应；
 * 3. idemKey 重复但参数哈希不一致 -> 明确拒绝（IDEMPOTENT_PARAM_MISMATCH）。
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository repo;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    public <T> T execute(String idemKey, String requestHash, Class<T> type, Supplier<T> action) {
        if (idemKey == null || idemKey.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "idemKey 不能为空");
        }
        Map<String, Object> existing = repo.find(idemKey);
        if (existing != null) {
            if (!requestHash.equals(existing.get("request_hash"))) {
                throw new BusinessException(ErrorCode.IDEMPOTENT_PARAM_MISMATCH,
                        "idemKey=" + idemKey + " 首次请求参数与本次不一致");
            }
            try {
                return objectMapper.readValue((String) existing.get("response_body"), type);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        T result = action.get();
        String body;
        try {
            body = objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        repo.insert(idemKey, requestHash, "OP", 200, body);
        return result;
    }

    public String hash(Object... parts) {
        try {
            String canonical = objectMapper.writeValueAsString(parts);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
