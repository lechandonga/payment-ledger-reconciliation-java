package com.github.lechandonga.paymentledgerreconciliationjava.domain;

public class BusinessException extends RuntimeException {
    public final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        super(errorCode.message + ": " + detail);
        this.errorCode = errorCode;
    }
}
