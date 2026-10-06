package com.github.lechandonga.paymentledgerreconciliationjava.api;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * In-memory idempotency registry. The first request with a given key stores its
 * parameter fingerprint and its accepted result. A replay with the same key and
 * identical parameters returns the stored result; the same key with different
 * parameters is rejected with IDEMPOTENCY_KEY_CONFLICT.
 */
@Service
public class IdempotencyService {

    private record Entry(String fingerprint, Object result) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * @param key         idempotency key supplied by the caller
     * @param fingerprint canonical representation of the request parameters
     * @param action      the real operation, executed at most once per key
     */
    @SuppressWarnings("unchecked")
    public synchronized <T> T execute(String key, String fingerprint, Supplier<T> action) {
        Entry existing = entries.get(key);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new RejectionException(RejectionException.Reason.IDEMPOTENCY_KEY_CONFLICT,
                        "idempotency key '" + key + "' was already used with different parameters");
            }
            return (T) existing.result();
        }
        T result = action.get();
        entries.put(key, new Entry(fingerprint, result));
        return result;
    }

    public void clear() {
        entries.clear();
    }
}
