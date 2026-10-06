package com.github.lechandonga.paymentledgerreconciliationjava.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ErrorResponse(String reason, String message) {
    }

    @ExceptionHandler(RejectionException.class)
    public ResponseEntity<ErrorResponse> onRejection(RejectionException ex) {
        HttpStatus status = switch (ex.getReason()) {
            case PAYMENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case IDEMPOTENCY_KEY_CONFLICT, BATCH_ALREADY_COMPLETED, BATCH_IN_PROGRESS ->
                    HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        log.info("rejected: {} - {}", ex.getReason(), ex.getMessage());
        return ResponseEntity.status(status)
                .body(new ErrorResponse(ex.getReason().name(), ex.getMessage()));
    }
}
