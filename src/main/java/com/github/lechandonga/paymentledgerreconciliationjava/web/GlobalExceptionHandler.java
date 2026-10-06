package com.github.lechandonga.paymentledgerreconciliationjava.web;

import com.github.lechandonga.paymentledgerreconciliationjava.domain.BusinessException;
import com.github.lechandonga.paymentledgerreconciliationjava.domain.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handle(BusinessException ex) {
        ErrorCode code = ex.errorCode;
        log.warn("[REJECT] code={} reason={}", code.name(), ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", false);
        body.put("errorCode", code.name());
        body.put("message", ex.getMessage());
        HttpStatus http = switch (code) {
            case PAYMENT_NOT_FOUND, REFUND_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case BATCH_CONCURRENT -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(http).body(body);
    }
}
